#include <jni.h>
#include <string>
#include <android/log.h>
#include <array>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <vector>
#include <memory>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <cstdio>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" {
#include "exfat.h"
#include "exfat_mkfs_bridge.h"
#include "ff.h"
#include "diskio.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/device.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/misc.h"
#include "ntfs-3g/unistr.h"
#include "ntfs-3g/volume.h"

int ntfs_mkntfs_main(int argc, char* argv[]);
void mkntfs_set_device_override(struct ntfs_device_operations* ops, void* private_data);
void mkntfs_clear_device_override(void);
extern int optind;
extern int opterr;
}

#include "Common/Tcdefs.h"
#include "Platform/Buffer.h"
#include "Platform/Exception.h"
#include "Platform/File.h"
#include "Core/RandomNumberGenerator.h"
#include "Volume/EncryptionAlgorithm.h"
#include "Volume/EncryptionModeXTS.h"
#include "Volume/Pkcs5Kdf.h"
#include "Volume/Volume.h"
#include "Volume/VolumeException.h"
#include "Volume/VolumePassword.h"
#include "Volume/Keyfile.h"
#include "Volume/VolumeHeader.h"
#include "Volume/VolumeLayout.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "cryptocore", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "cryptocore", __VA_ARGS__)

constexpr jlong VC_ERR_OPEN_PASSWORD = -1001;
constexpr jlong VC_ERR_OPEN_PROTECTION_PASSWORD = -1002;
constexpr jint VC_MOUNT_WARNING_NONE = 0;
constexpr jint VC_MOUNT_WARNING_NTFS_HIBERNATED_READONLY = 1;
constexpr jint VC_MOUNT_WARNING_NTFS_UNCLEAN_READONLY = 2;

enum class FsType {
    Unknown,
    ExFat,
    Ntfs,
    Fat
};

struct VcBlockDevice {
    shared_ptr<VeraCrypt::Volume> volume;
    uint64_t size = 0;
    uint32_t sectorSize = 512;
    bool readOnly = false;
    int64_t pos = 0;
};

struct HandleState {
    bool hidden = false;
    bool readOnly = false;
    FsType fs = FsType::Unknown;
    shared_ptr<VeraCrypt::Volume> volume;
    shared_ptr<VeraCrypt::File> file;
    std::unique_ptr<VcBlockDevice> blockDev;
    struct exfat exfatFs;
    bool exfatMounted = false;
    ntfs_volume* ntfsVol = nullptr;
    ntfs_device* ntfsDev = nullptr;
    bool ntfsMounted = false;
    FATFS fatFs{};
    bool fatMounted = false;
    BYTE fatDrive = 0xFF;
    int mountWarning = VC_MOUNT_WARNING_NONE;
};

std::mutex g_fatDriveMutex;
std::array<VcBlockDevice*, FF_VOLUMES> g_fatDrives{};
std::atomic<bool> g_cancelRequested{false};

int vcFatRegisterDrive(VcBlockDevice* dev);
void vcFatReleaseDrive(BYTE pdrv);
VcBlockDevice* vcFatGetDrive(BYTE pdrv);
extern "C" int vcShouldCancelCurrentOperation();

namespace {
std::unordered_map<long, std::unique_ptr<HandleState>> g_handles;
std::mutex g_mutex;
long g_nextHandle = 1;

inline bool isCancelRequested() {
    return g_cancelRequested.load(std::memory_order_relaxed);
}

inline int cancelRc() {
    errno = ECANCELED;
    return -ECANCELED;
}

inline bool throwIfCanceled() {
    if (!isCancelRequested()) return false;
    errno = ECANCELED;
    return true;
}

long addHandle(std::unique_ptr<HandleState> state) {
    std::lock_guard<std::mutex> lg(g_mutex);
    long h = g_nextHandle++;
    g_handles[h] = std::move(state);
    return h;
}

HandleState* getHandle(long h) {
    std::lock_guard<std::mutex> lg(g_mutex);
    auto it = g_handles.find(h);
    if (it == g_handles.end()) return nullptr;
    return it->second.get();
}

void dropHandle(long h) {
    std::lock_guard<std::mutex> lg(g_mutex);
    g_handles.erase(h);
}

std::string jstringToUtf8(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* raw = env->GetStringUTFChars(js, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(js, raw);
    return out;
}

std::vector<uint8_t> jbytes(JNIEnv* env, jbyteArray arr) {
    std::vector<uint8_t> out;
    if (!arr) return out;
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return out;
    out.resize(static_cast<size_t>(len));
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

std::vector<std::string> jstrings(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(jstringToUtf8(env, item));
        if (item) env->DeleteLocalRef(item);
    }
    return out;
}

std::string normalizePath(const std::string& path) {
    if (path.empty()) return "/";
    if (!path.empty() && path[0] == '/') return path;
    return "/" + path;
}

bool splitPath(const std::string& path, std::string& parent, std::string& name) {
    std::string normalized = normalizePath(path);
    while (normalized.size() > 1 && normalized.back() == '/')
        normalized.pop_back();
    if (normalized == "/")
        return false;
    size_t slash = normalized.find_last_of('/');
    if (slash == std::string::npos) {
        parent = "/";
        name = normalized;
    } else {
        parent = (slash == 0) ? "/" : normalized.substr(0, slash);
        name = normalized.substr(slash + 1);
    }
    return !name.empty();
}

std::string toLowerAscii(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return s;
}

shared_ptr<VeraCrypt::KeyfileList> makeKeyfileList(const std::vector<std::string>& keyfilePaths) {
    if (keyfilePaths.empty()) return shared_ptr<VeraCrypt::KeyfileList>();
    auto keyfiles = make_shared<VeraCrypt::KeyfileList>();
    for (const auto& path : keyfilePaths) {
        if (!path.empty()) {
            keyfiles->push_back(make_shared<VeraCrypt::Keyfile>(VeraCrypt::FilesystemPath(path)));
        }
    }
    if (keyfiles->empty()) return shared_ptr<VeraCrypt::KeyfileList>();
    return keyfiles;
}

shared_ptr<VeraCrypt::VolumePassword> applyKeyfilesToPassword(
        const shared_ptr<VeraCrypt::VolumePassword>& password,
        const std::vector<std::string>& keyfilePaths) {
    return VeraCrypt::Keyfile::ApplyListToPassword(makeKeyfileList(keyfilePaths), password, false);
}

struct FdGuard {
    int fd = -1;
    explicit FdGuard(int f) : fd(f) {}
    ~FdGuard() {
        if (fd >= 0) ::close(fd);
    }
    int release() {
        int out = fd;
        fd = -1;
        return out;
    }
};

shared_ptr<VeraCrypt::EncryptionAlgorithm> selectAlgorithm(const std::string& input) {
    const std::string name = toLowerAscii(input);
    if (name == "aes") return make_shared<VeraCrypt::AES>();
    if (name == "serpent") return make_shared<VeraCrypt::Serpent>();
    if (name == "twofish") return make_shared<VeraCrypt::Twofish>();
    if (name == "serpent_aes" || name == "aes_serpent") return make_shared<VeraCrypt::SerpentAES>();
    if (name == "twofish_serpent") return make_shared<VeraCrypt::TwofishSerpent>();
    if (name == "serpent_twofish_aes") return make_shared<VeraCrypt::SerpentTwofishAES>();
    if (name == "aes_twofish") return make_shared<VeraCrypt::AESTwofish>();
    if (name == "aes_twofish_serpent") return make_shared<VeraCrypt::AESTwofishSerpent>();
    return nullptr;
}

shared_ptr<VeraCrypt::Pkcs5Kdf> selectKdf(const std::string& input) {
    const std::string name = toLowerAscii(input);
    if (name == "sha512" || name == "hmac-sha-512") return make_shared<VeraCrypt::Pkcs5HmacSha512>();
    if (name == "whirlpool" || name == "hmac-whirlpool") return make_shared<VeraCrypt::Pkcs5HmacWhirlpool>();
    return nullptr;
}

static ssize_t vc_exfat_pread(void* user, void* buffer, size_t size, off_t offset);
static ssize_t vc_exfat_pwrite(void* user, const void* buffer, size_t size, off_t offset);
static int vc_exfat_fsync(void* user);
static int vc_exfat_close(void* user);
int formatFatVolume(const shared_ptr<VeraCrypt::Volume>& volume, bool readOnly);
static int ensureExfatDirectoryPath(HandleState& hs, const std::string& parentPath);
static int mkdirExfatEntry(HandleState& hs, const std::string& path);
static std::string fatDrivePath(BYTE pdrv, const std::string& path = {});
static int fatResultToErrno(FRESULT fr);
static bool mountFat(HandleState& hs);
static int ensureFatDirectoryPath(HandleState& hs, const std::string& parentPath);
static int listFat(HandleState& hs, const std::string& path, std::vector<std::string>& out);
static int readFatFile(HandleState& hs, const std::string& path, const std::string& destPath);
static int writeFatFile(HandleState& hs, const std::string& path, const std::string& srcPath);
static int mkdirFatEntry(HandleState& hs, const std::string& path);
static int deleteFatEntry(HandleState& hs, const std::string& path);
static int mkdirNtfsEntry(HandleState& hs, const std::string& path);
extern ntfs_device_operations vc_ntfs_ops;

int formatExfatVolume(const shared_ptr<VeraCrypt::Volume>& volume, bool readOnly) {
    if (!volume) return -EINVAL;
    if (throwIfCanceled()) return cancelRc();
    auto blockDev = std::make_unique<VcBlockDevice>();
    blockDev->volume = volume;
    blockDev->size = volume->GetSize();
    blockDev->sectorSize = static_cast<uint32_t>(volume->GetSectorSize());
    blockDev->readOnly = readOnly;

    struct exfat_dev_ops ops;
    std::memset(&ops, 0, sizeof(ops));
    ops.pread = vc_exfat_pread;
    ops.pwrite = vc_exfat_pwrite;
    ops.fsync = vc_exfat_fsync;
    ops.close = vc_exfat_close;

    struct exfat_dev* dev = exfat_open_ops(&ops, blockDev.get(), EXFAT_MODE_RW,
                                           static_cast<off_t>(blockDev->size));
    if (!dev) return isCancelRequested() ? cancelRc() : -EIO;
    int mkrc = vc_exfat_mkfs(dev, static_cast<off_t>(blockDev->size), "CRYPTVOL");
    int closeRc = exfat_close(dev);
    if (closeRc != 0) return isCancelRequested() ? cancelRc() : -EIO;
    if (mkrc != 0) return isCancelRequested() ? cancelRc() : -EIO;
    return 0;
}

int formatNtfsVolume(const shared_ptr<VeraCrypt::Volume>& volume, bool readOnly) {
    if (!volume) return -EINVAL;
    if (readOnly) return -EROFS;
    if (throwIfCanceled()) return cancelRc();

    auto blockDev = std::make_unique<VcBlockDevice>();
    blockDev->volume = volume;
    blockDev->size = volume->GetSize();
    blockDev->sectorSize = static_cast<uint32_t>(volume->GetSectorSize());
    blockDev->readOnly = false;

    uint32_t sectorSize = blockDev->sectorSize == 0 ? 512U : blockDev->sectorSize;
    uint64_t numSectors = blockDev->size / sectorSize;
    if (numSectors == 0) return -EINVAL;

    std::string label = "CRYPTVOL";
    std::string sectorSizeArg = std::to_string(sectorSize);
    std::string numSectorsArg = std::to_string(numSectors);
    std::string deviceName = "veracrypt";

    std::vector<char*> argv;
    argv.reserve(10);
    argv.push_back(const_cast<char*>("mkntfs"));
    argv.push_back(const_cast<char*>("-Q"));
    argv.push_back(const_cast<char*>("-F"));
    argv.push_back(const_cast<char*>("-L"));
    argv.push_back(label.data());
    argv.push_back(const_cast<char*>("-s"));
    argv.push_back(sectorSizeArg.data());
    argv.push_back(deviceName.data());
    argv.push_back(numSectorsArg.data());
    argv.push_back(nullptr);

    ntfs_set_locale();
    ntfs_set_char_encoding("UTF-8");
    LOGI("formatNtfsVolume: size=%llu sector=%u numSectors=%llu",
         static_cast<unsigned long long>(blockDev->size),
         static_cast<unsigned>(sectorSize),
         static_cast<unsigned long long>(numSectors));

    mkntfs_set_device_override(&vc_ntfs_ops, blockDev.get());
    optind = 1;
    opterr = 0;
    errno = 0;
    int mkrc = ntfs_mkntfs_main(static_cast<int>(argv.size() - 1), argv.data());
    int savedErrno = errno;
    mkntfs_clear_device_override();
    if (mkrc != 0) {
        if (isCancelRequested()) return cancelRc();
        LOGE("mkntfs failed: rc=%d errno=%d", mkrc, savedErrno);
        return savedErrno > 0 ? -savedErrno : -EIO;
    }
    LOGI("formatNtfsVolume: mkntfs success");

    try {
        auto file = volume->GetFile();
        if (file) file->Flush();
    } catch (...) {
        return -EIO;
    }
    return 0;
}

int formatFatVolume(const shared_ptr<VeraCrypt::Volume>& volume, bool readOnly) {
    if (!volume) return -EINVAL;
    if (readOnly) return -EROFS;
    if (throwIfCanceled()) return cancelRc();

    auto blockDev = std::make_unique<VcBlockDevice>();
    blockDev->volume = volume;
    blockDev->size = volume->GetSize();
    blockDev->sectorSize = static_cast<uint32_t>(volume->GetSectorSize());
    if (blockDev->sectorSize == 0) blockDev->sectorSize = 512;
    blockDev->readOnly = false;

    int drive = vcFatRegisterDrive(blockDev.get());
    if (drive < 0) return drive;

    FATFS fs{};
    std::string driveRoot = fatDrivePath(static_cast<BYTE>(drive));
    std::vector<BYTE> work(64U * 1024U);

    MKFS_PARM mkfs{};
    mkfs.fmt = static_cast<BYTE>(FM_ANY | FM_SFD);

    FRESULT fr = f_mkfs(driveRoot.c_str(), &mkfs, work.data(), static_cast<UINT>(work.size()));
    if (fr == FR_OK) {
        fr = f_mount(&fs, driveRoot.c_str(), 1);
        if (fr == FR_OK) {
            std::string labelSpec = driveRoot + "CRYPTVOL";
            FRESULT lfr = f_setlabel(labelSpec.c_str());
            if (lfr != FR_OK) {
                LOGE("formatFatVolume: f_setlabel failed fr=%d", static_cast<int>(lfr));
            }
            FRESULT ufr = f_mount(nullptr, driveRoot.c_str(), 0);
            if (ufr != FR_OK) {
                LOGE("formatFatVolume: unmount after label failed fr=%d", static_cast<int>(ufr));
            }
        }
    }

    vcFatReleaseDrive(static_cast<BYTE>(drive));

    if (fr != FR_OK) {
        if (isCancelRequested()) return cancelRc();
        LOGE("formatFatVolume: f_mkfs/f_mount failed fr=%d", static_cast<int>(fr));
        return fatResultToErrno(fr);
    }

    try {
        auto file = volume->GetFile();
        if (file) file->Flush();
    } catch (...) {
        return -EIO;
    }
    return 0;
}

int writeVolumeHeaders(const shared_ptr<VeraCrypt::File>& file,
                       uint64_t hostSize,
                       uint64_t requestedSize,
                       VeraCrypt::VolumeType::Enum type,
                       const shared_ptr<VeraCrypt::EncryptionAlgorithm>& ea,
                       const shared_ptr<VeraCrypt::Pkcs5Kdf>& kdf,
                       const shared_ptr<VeraCrypt::VolumePassword>& pwd,
                       int pim) {
    if (!file || !ea || !kdf || !pwd) return -EINVAL;
    if (requestedSize == 0 || requestedSize > hostSize) return -EINVAL;

    shared_ptr<VeraCrypt::VolumeLayout> layout;
    if (type == VeraCrypt::VolumeType::Hidden)
        layout = make_shared<VeraCrypt::VolumeLayoutV2Hidden>();
    else
        layout = make_shared<VeraCrypt::VolumeLayoutV2Normal>();

    auto header = layout->GetHeader();
    VeraCrypt::SecureBuffer headerBuffer(layout->GetHeaderSize());

    VeraCrypt::VolumeHeaderCreationOptions headerOptions;
    headerOptions.EA = ea;
    headerOptions.Kdf = kdf;
    headerOptions.Type = type;
    headerOptions.SectorSize = TC_SECTOR_SIZE_FILE_HOSTED_VOLUME;
    if (type == VeraCrypt::VolumeType::Hidden)
        headerOptions.VolumeDataStart = hostSize - layout->GetHeaderSize() * 2 - requestedSize;
    else
        headerOptions.VolumeDataStart = layout->GetHeaderSize() * 2;
    headerOptions.VolumeDataSize = layout->GetMaxDataSize(requestedSize);
    if (headerOptions.VolumeDataSize < 1) return -EINVAL;

    VeraCrypt::SecureBuffer masterKey(ea->GetKeySize() * 2);
    VeraCrypt::RandomNumberGenerator::GetData(masterKey);
    if (std::memcmp(masterKey.Ptr(), masterKey.Ptr() + masterKey.Size() / 2, masterKey.Size() / 2) == 0)
        masterKey[0] ^= 0xA5;
    headerOptions.DataKey = masterKey;

    VeraCrypt::SecureBuffer salt(VeraCrypt::VolumeHeader::GetSaltSize());
    VeraCrypt::RandomNumberGenerator::GetData(salt);
    headerOptions.Salt = salt;

    VeraCrypt::SecureBuffer headerKey(VeraCrypt::VolumeHeader::GetLargestSerializedKeySize());
    kdf->DeriveKey(headerKey, *pwd, pim, salt);
    headerOptions.HeaderKey = headerKey;

    header->Create(headerBuffer, headerOptions);
    if (layout->GetHeaderOffset() >= 0)
        file->SeekAt(layout->GetHeaderOffset());
    else
        file->SeekEnd(layout->GetHeaderOffset());
    file->Write(headerBuffer);

    VeraCrypt::SecureBuffer backupHeader(layout->GetHeaderSize());
    VeraCrypt::SecureBuffer backupSalt(VeraCrypt::VolumeHeader::GetSaltSize());
    VeraCrypt::RandomNumberGenerator::GetData(backupSalt);
    kdf->DeriveKey(headerKey, *pwd, pim, backupSalt);
    header->EncryptNew(backupHeader, backupSalt, headerKey, kdf);
    file->SeekEnd(layout->GetBackupHeaderOffset());
    file->Write(backupHeader);
    file->Flush();
    return 0;
}

int createVolumeOnFd(int fd,
                     uint64_t sizeBytes,
                     const std::string& filesystem,
                     const std::string& algorithm,
                     const std::string& hash,
                     int pim,
                     const std::vector<uint8_t>& pwdBytes,
                     const std::vector<std::string>& keyfilePaths,
                     uint64_t hiddenSizeBytes,
                     const std::vector<uint8_t>& hiddenPwdBytes,
                     const std::vector<std::string>& hiddenKeyfilePaths,
                     int hiddenPim) {
    LOGI("createVolumeOnFd: fd=%d size=%llu fs=%s algo=%s hash=%s hidden=%llu",
         fd,
         static_cast<unsigned long long>(sizeBytes),
         filesystem.c_str(),
         algorithm.c_str(),
         hash.c_str(),
         static_cast<unsigned long long>(hiddenSizeBytes));
    if (fd < 0) {
        LOGE("createVolumeOnFd: invalid fd");
        return -EINVAL;
    }
    if (pwdBytes.empty()) {
        LOGE("createVolumeOnFd: password is empty");
        return -EINVAL;
    }
    if (sizeBytes < TC_MIN_VOLUME_SIZE) {
        LOGE("createVolumeOnFd: size below minimum host size: %llu < %llu",
             static_cast<unsigned long long>(sizeBytes),
             static_cast<unsigned long long>(TC_MIN_VOLUME_SIZE));
        return -EINVAL;
    }
    if (hiddenSizeBytes >= sizeBytes) {
        LOGE("createVolumeOnFd: hidden size must be smaller than outer size");
        return -EINVAL;
    }
    if (throwIfCanceled()) return cancelRc();
    const std::string fs = toLowerAscii(filesystem);
    if (fs != "exfat" && fs != "ntfs" && fs != "fat") {
        LOGE("createVolumeOnFd: unsupported filesystem=%s", fs.c_str());
        return -ENOTSUP;
    }
    VeraCrypt::VolumeLayoutV2Normal normalLayout;
    const uint64_t dataAreaSize = normalLayout.GetMaxDataSize(sizeBytes);
    const uint64_t minFsSize = fs == "ntfs" ? TC_MIN_NTFS_FS_SIZE :
        (fs == "exfat" ? TC_MIN_EXFAT_FS_SIZE : TC_MIN_FAT_FS_SIZE);
    if (dataAreaSize < minFsSize) {
        LOGE("createVolumeOnFd: %s data area too small: %llu < %llu",
             fs.c_str(),
             static_cast<unsigned long long>(dataAreaSize),
             static_cast<unsigned long long>(minFsSize));
        return -EINVAL;
    }
    if (hiddenSizeBytes > 0) {
        VeraCrypt::VolumeLayoutV2Hidden hiddenLayout;
        const uint64_t hiddenDataAreaSize = hiddenLayout.GetMaxDataSize(hiddenSizeBytes);
        if (sizeBytes < TC_MIN_HIDDEN_VOLUME_HOST_SIZE) {
            LOGE("createVolumeOnFd: outer size below hidden-volume host minimum: %llu < %llu",
                 static_cast<unsigned long long>(sizeBytes),
                 static_cast<unsigned long long>(TC_MIN_HIDDEN_VOLUME_HOST_SIZE));
            return -EINVAL;
        }
        if (hiddenSizeBytes < TC_MIN_HIDDEN_VOLUME_SIZE) {
            LOGE("createVolumeOnFd: hidden size below minimum: %llu < %llu",
                 static_cast<unsigned long long>(hiddenSizeBytes),
                 static_cast<unsigned long long>(TC_MIN_HIDDEN_VOLUME_SIZE));
            return -EINVAL;
        }
        if (hiddenDataAreaSize < minFsSize) {
            LOGE("createVolumeOnFd: hidden %s data area too small: %llu < %llu",
                 fs.c_str(),
                 static_cast<unsigned long long>(hiddenDataAreaSize),
                 static_cast<unsigned long long>(minFsSize));
            return -EINVAL;
        }
    }
    if (::ftruncate(fd, static_cast<off_t>(sizeBytes)) != 0) {
        LOGE("createVolumeOnFd: ftruncate failed errno=%d", errno);
        return -errno;
    }
    if (throwIfCanceled()) return cancelRc();

    auto ea = selectAlgorithm(algorithm);
    if (!ea) ea = make_shared<VeraCrypt::AES>();
    auto kdf = selectKdf(hash);
    if (!kdf) kdf = make_shared<VeraCrypt::Pkcs5HmacSha512>();
    auto outerBasePwd = make_shared<VeraCrypt::VolumePassword>(pwdBytes.data(), pwdBytes.size());
    auto pwd = applyKeyfilesToPassword(outerBasePwd, keyfilePaths);
    if (!pwd || pwd->Size() < 1) return -EINVAL;
    auto hiddenPwd = pwd;
    int effectiveHiddenPim = pim;
    if (hiddenSizeBytes > 0) {
        auto hiddenBasePwd = outerBasePwd;
        if (!hiddenPwdBytes.empty()) {
            hiddenBasePwd = make_shared<VeraCrypt::VolumePassword>(hiddenPwdBytes.data(), hiddenPwdBytes.size());
        }
        hiddenPwd = applyKeyfilesToPassword(hiddenBasePwd, hiddenKeyfilePaths);
        if (!hiddenPwd || hiddenPwd->Size() < 1) return -EINVAL;
        if (hiddenPim > 0) {
            effectiveHiddenPim = hiddenPim;
        }
    }

    FdGuard fdGuard(fd);
    try {
        VeraCrypt::RandomNumberGenerator::Start();
        VeraCrypt::RandomNumberGenerator::SetHash(kdf->GetHash());
        if (throwIfCanceled()) {
            VeraCrypt::RandomNumberGenerator::Stop();
            return cancelRc();
        }

        auto file = make_shared<VeraCrypt::File>();
        file->AssignSystemHandle(fdGuard.release(), false);
        int rc = writeVolumeHeaders(file, sizeBytes, sizeBytes, VeraCrypt::VolumeType::Normal, ea, kdf, pwd, pim);
        if (rc != 0) {
            LOGE("createVolumeOnFd: writeVolumeHeaders(normal) failed rc=%d", rc);
            VeraCrypt::RandomNumberGenerator::Stop();
            return rc;
        }

        auto volume = make_shared<VeraCrypt::Volume>();
        volume->Open(file, pwd, pim, shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(), false,
                     VeraCrypt::VolumeProtection::None, shared_ptr<VeraCrypt::VolumePassword>(), 0,
                     shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(),
                     VeraCrypt::VolumeType::Normal, false, false);
        if (throwIfCanceled()) {
            VeraCrypt::RandomNumberGenerator::Stop();
            return cancelRc();
        }

        if (fs == "ntfs")
            rc = formatNtfsVolume(volume, false);
        else if (fs == "fat")
            rc = formatFatVolume(volume, false);
        else
            rc = formatExfatVolume(volume, false);
        LOGI("createVolumeOnFd: format rc=%d fs=%s", rc, fs.c_str());
        auto volFile = volume->GetFile();
        if (volFile) volFile->Flush();
        volume.reset();
        if (rc != 0) {
            LOGE("createVolumeOnFd: format failed rc=%d", rc);
            VeraCrypt::RandomNumberGenerator::Stop();
            return rc;
        }

        if (hiddenSizeBytes > 0) {
            rc = writeVolumeHeaders(
                    file, sizeBytes, hiddenSizeBytes, VeraCrypt::VolumeType::Hidden, ea, kdf, hiddenPwd, effectiveHiddenPim);
            if (rc != 0) {
                LOGE("createVolumeOnFd: writeVolumeHeaders(hidden) failed rc=%d", rc);
                VeraCrypt::RandomNumberGenerator::Stop();
                return rc;
            }

            auto hiddenVolume = make_shared<VeraCrypt::Volume>();
            hiddenVolume->Open(file, hiddenPwd, effectiveHiddenPim,
                               shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(), false,
                               VeraCrypt::VolumeProtection::None, shared_ptr<VeraCrypt::VolumePassword>(), 0,
                               shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(),
                               VeraCrypt::VolumeType::Hidden, false, false);
            if (throwIfCanceled()) {
                VeraCrypt::RandomNumberGenerator::Stop();
                return cancelRc();
            }

            if (fs == "ntfs")
                rc = formatNtfsVolume(hiddenVolume, false);
            else if (fs == "fat")
                rc = formatFatVolume(hiddenVolume, false);
            else
                rc = formatExfatVolume(hiddenVolume, false);
            LOGI("createVolumeOnFd: hidden format rc=%d fs=%s", rc, fs.c_str());
            auto hiddenVolFile = hiddenVolume->GetFile();
            if (hiddenVolFile) hiddenVolFile->Flush();
            hiddenVolume.reset();
            if (rc != 0) {
                LOGE("createVolumeOnFd: hidden format failed rc=%d", rc);
                VeraCrypt::RandomNumberGenerator::Stop();
                return rc;
            }
        }

        VeraCrypt::RandomNumberGenerator::Stop();
        return rc;
    } catch (const VeraCrypt::SystemException& e) {
        if (e.GetErrorCode() == ECANCELED || isCancelRequested()) {
            try { VeraCrypt::RandomNumberGenerator::Stop(); } catch (...) {}
            return cancelRc();
        }
        LOGE("createVolumeOnFd system error: %s (errno=%lld)", e.what(), static_cast<long long>(e.GetErrorCode()));
    } catch (const VeraCrypt::Exception& e) {
        if (isCancelRequested()) {
            try { VeraCrypt::RandomNumberGenerator::Stop(); } catch (...) {}
            return cancelRc();
        }
        LOGE("createVolumeOnFd VC error: %s", e.what());
    } catch (const std::exception& e) {
        if (isCancelRequested()) {
            try { VeraCrypt::RandomNumberGenerator::Stop(); } catch (...) {}
            return cancelRc();
        }
        LOGE("createVolumeOnFd std error: %s", e.what());
    } catch (...) {
        if (isCancelRequested()) {
            try { VeraCrypt::RandomNumberGenerator::Stop(); } catch (...) {}
            return cancelRc();
        }
        LOGE("createVolumeOnFd unknown error");
    }
    try { VeraCrypt::RandomNumberGenerator::Stop(); } catch (...) {}
    return -EIO;
}

static ssize_t vc_exfat_pread(void* user, void* buffer, size_t size, off_t offset) {
    auto dev = static_cast<VcBlockDevice*>(user);
    if (!dev || !dev->volume || offset < 0) return -EIO;
    if (isCancelRequested()) return cancelRc();
    uint64_t uoffset = static_cast<uint64_t>(offset);
    if (uoffset + size > dev->size) return -EIO;

    uint64_t sector = dev->sectorSize;
    uint64_t alignedStart = (uoffset / sector) * sector;
    uint64_t alignedEnd = ((uoffset + size + sector - 1) / sector) * sector;
    size_t alignedSize = static_cast<size_t>(alignedEnd - alignedStart);

    try {
        if (alignedStart == uoffset && alignedSize == size) {
            VeraCrypt::BufferPtr buf(reinterpret_cast<uint8_t*>(buffer), size);
            dev->volume->ReadSectors(buf, alignedStart);
            return static_cast<ssize_t>(size);
        }

        std::vector<uint8_t> tmp(alignedSize);
        VeraCrypt::BufferPtr buf(tmp.data(), tmp.size());
        dev->volume->ReadSectors(buf, alignedStart);
        std::memcpy(buffer, tmp.data() + (uoffset - alignedStart), size);
        return static_cast<ssize_t>(size);
    } catch (...) {
        return isCancelRequested() ? cancelRc() : -EIO;
    }
}

static ssize_t vc_exfat_pwrite(void* user, const void* buffer, size_t size, off_t offset) {
    auto dev = static_cast<VcBlockDevice*>(user);
    if (!dev || !dev->volume || offset < 0) return -EIO;
    if (dev->readOnly) return -EROFS;
    if (isCancelRequested()) return cancelRc();
    uint64_t uoffset = static_cast<uint64_t>(offset);
    if (uoffset + size > dev->size) return -EIO;

    uint64_t sector = dev->sectorSize;
    uint64_t alignedStart = (uoffset / sector) * sector;
    uint64_t alignedEnd = ((uoffset + size + sector - 1) / sector) * sector;
    size_t alignedSize = static_cast<size_t>(alignedEnd - alignedStart);

    try {
        if (alignedStart == uoffset && alignedSize == size) {
            VeraCrypt::ConstBufferPtr buf(reinterpret_cast<const uint8_t*>(buffer), size);
            dev->volume->WriteSectors(buf, alignedStart);
            return static_cast<ssize_t>(size);
        }

        std::vector<uint8_t> tmp(alignedSize);
        VeraCrypt::BufferPtr readBuf(tmp.data(), tmp.size());
        dev->volume->ReadSectors(readBuf, alignedStart);
        std::memcpy(tmp.data() + (uoffset - alignedStart), buffer, size);
        VeraCrypt::ConstBufferPtr writeBuf(tmp.data(), tmp.size());
        dev->volume->WriteSectors(writeBuf, alignedStart);
        return static_cast<ssize_t>(size);
    } catch (...) {
        return isCancelRequested() ? cancelRc() : -EIO;
    }
}

static int vc_exfat_fsync(void* user) {
    auto dev = static_cast<VcBlockDevice*>(user);
    if (!dev || !dev->volume) return -EIO;
    try {
        auto file = dev->volume->GetFile();
        if (file) file->Flush();
        return 0;
    } catch (...) {
        return -EIO;
    }
}

static int vc_exfat_close(void* /*user*/) {
    return 0;
}

static s64 vc_ntfs_pread(ntfs_device* dev, void* buffer, s64 count, s64 offset) {
    if (!dev || !buffer || count < 0 || offset < 0) {
        errno = EINVAL;
        return -1;
    }
    if (isCancelRequested()) {
        errno = ECANCELED;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev || !bdev->volume) {
        errno = EIO;
        return -1;
    }
    uint64_t uoffset = static_cast<uint64_t>(offset);
    if (uoffset >= bdev->size) return 0;
    uint64_t avail = bdev->size - uoffset;
    if (static_cast<uint64_t>(count) > avail)
        count = static_cast<s64>(avail);
    size_t size = static_cast<size_t>(count);
    if (size == 0) return 0;

    uint64_t sector = bdev->sectorSize;
    uint64_t alignedStart = (uoffset / sector) * sector;
    uint64_t alignedEnd = ((uoffset + size + sector - 1) / sector) * sector;
    size_t alignedSize = static_cast<size_t>(alignedEnd - alignedStart);

    try {
        if (alignedStart == uoffset && alignedSize == size) {
            VeraCrypt::BufferPtr buf(reinterpret_cast<uint8_t*>(buffer), size);
            bdev->volume->ReadSectors(buf, alignedStart);
            return static_cast<s64>(size);
        }

        std::vector<uint8_t> tmp(alignedSize);
        VeraCrypt::BufferPtr buf(tmp.data(), tmp.size());
        bdev->volume->ReadSectors(buf, alignedStart);
        std::memcpy(buffer, tmp.data() + (uoffset - alignedStart), size);
        return static_cast<s64>(size);
    } catch (...) {
        errno = isCancelRequested() ? ECANCELED : EIO;
        return -1;
    }
}

static s64 vc_ntfs_pwrite(ntfs_device* dev, const void* buffer, s64 count, s64 offset) {
    if (!dev || !buffer || count < 0 || offset < 0) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev || !bdev->volume) {
        errno = EIO;
        return -1;
    }
    if (isCancelRequested()) {
        errno = ECANCELED;
        return -1;
    }
    if (bdev->readOnly || NDevReadOnly(dev)) {
        errno = EROFS;
        return -1;
    }
    uint64_t uoffset = static_cast<uint64_t>(offset);
    if (uoffset + static_cast<uint64_t>(count) > bdev->size) {
        errno = ENOSPC;
        return -1;
    }
    size_t size = static_cast<size_t>(count);
    if (size == 0) return 0;

    uint64_t sector = bdev->sectorSize;
    uint64_t alignedStart = (uoffset / sector) * sector;
    uint64_t alignedEnd = ((uoffset + size + sector - 1) / sector) * sector;
    size_t alignedSize = static_cast<size_t>(alignedEnd - alignedStart);

    try {
        if (alignedStart == uoffset && alignedSize == size) {
            VeraCrypt::ConstBufferPtr buf(reinterpret_cast<const uint8_t*>(buffer), size);
            bdev->volume->WriteSectors(buf, alignedStart);
            return static_cast<s64>(size);
        }

        std::vector<uint8_t> tmp(alignedSize);
        VeraCrypt::BufferPtr readBuf(tmp.data(), tmp.size());
        bdev->volume->ReadSectors(readBuf, alignedStart);
        std::memcpy(tmp.data() + (uoffset - alignedStart), buffer, size);
        VeraCrypt::ConstBufferPtr writeBuf(tmp.data(), tmp.size());
        bdev->volume->WriteSectors(writeBuf, alignedStart);
        return static_cast<s64>(size);
    } catch (...) {
        errno = isCancelRequested() ? ECANCELED : EIO;
        return -1;
    }
}

static int vc_ntfs_open(ntfs_device* dev, int flags) {
    if (!dev) {
        errno = EINVAL;
        return -1;
    }
    if (NDevOpen(dev)) {
        errno = EBUSY;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev || !bdev->volume) {
        errno = EINVAL;
        return -1;
    }
    if ((flags & O_RDWR) == O_RDWR) {
        if (bdev->readOnly) {
            errno = EROFS;
            return -1;
        }
        NDevClearReadOnly(dev);
    } else {
        NDevSetReadOnly(dev);
    }
    bdev->pos = 0;
    NDevSetOpen(dev);
    return 0;
}

static int vc_ntfs_close(ntfs_device* dev) {
    if (!dev) {
        errno = EINVAL;
        return -1;
    }
    if (NDevOpen(dev))
        NDevClearOpen(dev);
    return 0;
}

static s64 vc_ntfs_seek(ntfs_device* dev, s64 offset, int whence) {
    if (!dev) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev) {
        errno = EINVAL;
        return -1;
    }
    s64 base = 0;
    switch (whence) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = bdev->pos; break;
        case SEEK_END: base = static_cast<s64>(bdev->size); break;
        default:
            errno = EINVAL;
            return -1;
    }
    s64 newpos = base + offset;
    if (newpos < 0) {
        errno = EINVAL;
        return -1;
    }
    bdev->pos = newpos;
    return newpos;
}

static s64 vc_ntfs_read(ntfs_device* dev, void* buffer, s64 count) {
    if (!dev || !buffer || count < 0) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev) {
        errno = EINVAL;
        return -1;
    }
    s64 n = vc_ntfs_pread(dev, buffer, count, bdev->pos);
    if (n > 0)
        bdev->pos += n;
    return n;
}

static s64 vc_ntfs_write(ntfs_device* dev, const void* buffer, s64 count) {
    if (!dev || !buffer || count < 0) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev) {
        errno = EINVAL;
        return -1;
    }
    s64 n = vc_ntfs_pwrite(dev, buffer, count, bdev->pos);
    if (n > 0)
        bdev->pos += n;
    return n;
}

static int vc_ntfs_sync(ntfs_device* dev) {
    if (!dev) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev || !bdev->volume) return 0;
    try {
        auto file = bdev->volume->GetFile();
        if (file) file->Flush();
        return 0;
    } catch (...) {
        errno = EIO;
        return -1;
    }
}

static int vc_ntfs_stat(ntfs_device* dev, struct stat* st) {
    if (!dev || !st) {
        errno = EINVAL;
        return -1;
    }
    auto bdev = static_cast<VcBlockDevice*>(dev->d_private);
    if (!bdev) {
        errno = EINVAL;
        return -1;
    }
    std::memset(st, 0, sizeof(*st));
    st->st_mode = S_IFREG;
    st->st_size = static_cast<off_t>(bdev->size);
    return 0;
}

static int vc_ntfs_ioctl(ntfs_device* /*dev*/, unsigned long /*request*/, void* /*argp*/) {
    errno = ENOTTY;
    return -1;
}

ntfs_device_operations vc_ntfs_ops = {
        vc_ntfs_open,
        vc_ntfs_close,
        vc_ntfs_seek,
        vc_ntfs_read,
        vc_ntfs_write,
        vc_ntfs_pread,
        vc_ntfs_pwrite,
        vc_ntfs_sync,
        vc_ntfs_stat,
        vc_ntfs_ioctl
};

FsType detectFs(const shared_ptr<VeraCrypt::Volume>& volume) {
    if (!volume) return FsType::Unknown;
    try {
        size_t sectorSize = volume->GetSectorSize();
        std::vector<uint8_t> buf(sectorSize);
        VeraCrypt::BufferPtr bp(buf.data(), buf.size());
        volume->ReadSectors(bp, 0);
        if (buf.size() >= 11 && std::memcmp(buf.data() + 3, "EXFAT   ", 8) == 0)
            return FsType::ExFat;
        if (buf.size() >= 11 && std::memcmp(buf.data() + 3, "NTFS    ", 8) == 0)
            return FsType::Ntfs;
        if (buf.size() >= 62 &&
            (std::memcmp(buf.data() + 54, "FAT12   ", 8) == 0 ||
             std::memcmp(buf.data() + 54, "FAT16   ", 8) == 0)) {
            return FsType::Fat;
        }
        if (buf.size() >= 90 && std::memcmp(buf.data() + 82, "FAT32   ", 8) == 0)
            return FsType::Fat;
    } catch (...) {
    }
    return FsType::Unknown;
}

bool mountExfat(HandleState& hs) {
    if (isCancelRequested()) {
        errno = ECANCELED;
        return false;
    }
    hs.blockDev = std::make_unique<VcBlockDevice>();
    hs.blockDev->volume = hs.volume;
    hs.blockDev->size = hs.volume->GetSize();
    hs.blockDev->sectorSize = static_cast<uint32_t>(hs.volume->GetSectorSize());
    hs.blockDev->readOnly = hs.readOnly;

    struct exfat_dev_ops ops;
    std::memset(&ops, 0, sizeof(ops));
    ops.pread = vc_exfat_pread;
    ops.pwrite = vc_exfat_pwrite;
    ops.fsync = vc_exfat_fsync;
    ops.close = vc_exfat_close;

    struct exfat_dev* dev = exfat_open_ops(&ops, hs.blockDev.get(),
                                           hs.readOnly ? EXFAT_MODE_RO : EXFAT_MODE_RW,
                                           static_cast<off_t>(hs.blockDev->size));
    if (!dev) {
        if (isCancelRequested()) errno = ECANCELED;
        LOGE("exfat_open_ops failed");
        return false;
    }
    int rc = exfat_mount_dev(&hs.exfatFs, dev, hs.readOnly ? "ro" : "");
    if (rc != 0) {
        if (isCancelRequested()) errno = ECANCELED;
        LOGE("exfat_mount_dev failed: %d", rc);
        return false;
    }
    hs.exfatMounted = true;
    return true;
}

bool mountNtfs(HandleState& hs, int* mountStatus) {
    if (isCancelRequested()) {
        if (mountStatus) *mountStatus = NTFS_VOLUME_UNKNOWN_REASON;
        errno = ECANCELED;
        return false;
    }
    hs.blockDev = std::make_unique<VcBlockDevice>();
    hs.blockDev->volume = hs.volume;
    hs.blockDev->size = hs.volume->GetSize();
    hs.blockDev->sectorSize = static_cast<uint32_t>(hs.volume->GetSectorSize());
    hs.blockDev->readOnly = hs.readOnly;

    ntfs_set_locale();
    ntfs_set_char_encoding("UTF-8");

    ntfs_device* dev = ntfs_device_alloc("veracrypt", 0, &vc_ntfs_ops, hs.blockDev.get());
    if (!dev) {
        if (isCancelRequested()) errno = ECANCELED;
        LOGE("ntfs_device_alloc failed: %d", errno);
        if (mountStatus) *mountStatus = NTFS_VOLUME_UNKNOWN_REASON;
        return false;
    }

    ntfs_mount_flags flags = hs.readOnly
            ? (NTFS_MNT_RDONLY | NTFS_MNT_FORENSIC | NTFS_MNT_IGNORE_HIBERFILE)
            : (NTFS_MNT_EXCLUSIVE | NTFS_MNT_RECOVER);
    ntfs_volume* vol = ntfs_device_mount(dev, flags);
    if (!vol) {
        int err = errno;
        int status = ntfs_volume_error(err);
        if (isCancelRequested()) {
            err = ECANCELED;
            status = NTFS_VOLUME_UNKNOWN_REASON;
        }
        LOGE("ntfs_device_mount failed: errno=%d status=%d", err, status);
        ntfs_device_free(dev);
        if (mountStatus) *mountStatus = status;
        return false;
    }

    ntfs_set_shown_files(vol, TRUE, TRUE, FALSE);
    hs.ntfsDev = dev;
    hs.ntfsVol = vol;
    hs.ntfsMounted = true;
    if (mountStatus) *mountStatus = NTFS_VOLUME_OK;
    return true;
}

std::string fatDrivePath(BYTE pdrv, const std::string& path) {
    std::string out = std::to_string(static_cast<unsigned>(pdrv)) + ":";
    if (path.empty()) return out;
    out += normalizePath(path);
    return out;
}

int fatResultToErrno(FRESULT fr) {
    switch (fr) {
        case FR_OK: return 0;
        case FR_DISK_ERR:
        case FR_INT_ERR:
        case FR_MKFS_ABORTED:
            return -EIO;
        case FR_NOT_READY:
        case FR_INVALID_DRIVE:
        case FR_NOT_ENABLED:
            return -ENODEV;
        case FR_NO_FILE:
        case FR_NO_PATH:
            return -ENOENT;
        case FR_INVALID_NAME:
        case FR_INVALID_PARAMETER:
            return -EINVAL;
        case FR_DENIED:
            return -EACCES;
        case FR_EXIST:
            return -EEXIST;
        case FR_INVALID_OBJECT:
            return -EBADF;
        case FR_WRITE_PROTECTED:
            return -EROFS;
        case FR_NO_FILESYSTEM:
            return -ENODATA;
        case FR_TIMEOUT:
            return -ETIMEDOUT;
        case FR_LOCKED:
            return -EBUSY;
        case FR_NOT_ENOUGH_CORE:
            return -ENOMEM;
        case FR_TOO_MANY_OPEN_FILES:
            return -EMFILE;
        default:
            return -EIO;
    }
}

bool mountFat(HandleState& hs) {
    if (isCancelRequested()) {
        errno = ECANCELED;
        return false;
    }
    hs.blockDev = std::make_unique<VcBlockDevice>();
    hs.blockDev->volume = hs.volume;
    hs.blockDev->size = hs.volume->GetSize();
    hs.blockDev->sectorSize = static_cast<uint32_t>(hs.volume->GetSectorSize());
    if (hs.blockDev->sectorSize == 0) hs.blockDev->sectorSize = 512;
    hs.blockDev->readOnly = hs.readOnly;

    int drive = vcFatRegisterDrive(hs.blockDev.get());
    if (drive < 0) {
        if (isCancelRequested()) errno = ECANCELED;
        LOGE("vcFatRegisterDrive failed rc=%d", drive);
        return false;
    }
    hs.fatDrive = static_cast<BYTE>(drive);

    FRESULT fr = f_mount(&hs.fatFs, fatDrivePath(hs.fatDrive).c_str(), 1);
    if (fr != FR_OK) {
        if (isCancelRequested()) errno = ECANCELED;
        LOGE("f_mount FAT failed fr=%d", static_cast<int>(fr));
        vcFatReleaseDrive(hs.fatDrive);
        hs.fatDrive = 0xFF;
        return false;
    }
    hs.fatMounted = true;
    return true;
}

int listFat(HandleState& hs, const std::string& path, std::vector<std::string>& out) {
    if (!hs.fatMounted) return -EINVAL;
    DIR dir{};
    FILINFO info{};
    const std::string fp = fatDrivePath(hs.fatDrive, path);
    FRESULT fr = f_opendir(&dir, fp.c_str());
    if (fr != FR_OK) return fatResultToErrno(fr);

    while (true) {
        fr = f_readdir(&dir, &info);
        if (fr != FR_OK) {
            f_closedir(&dir);
            return fatResultToErrno(fr);
        }
        if (info.fname[0] == '\0') break;
        std::string entry(info.fname);
        if (entry == "." || entry == "..") continue;
        if (info.fattrib & AM_DIR)
            entry += "/";
        out.push_back(entry);
    }
    f_closedir(&dir);
    return 0;
}

int readFatFile(HandleState& hs, const std::string& path, const std::string& destPath) {
    if (!hs.fatMounted) return -EINVAL;
    const std::string fp = fatDrivePath(hs.fatDrive, path);
    FILINFO st{};
    FRESULT fr = f_stat(fp.c_str(), &st);
    if (fr != FR_OK) return fatResultToErrno(fr);
    if (st.fattrib & AM_DIR) return -EISDIR;

    FIL file{};
    fr = f_open(&file, fp.c_str(), FA_READ | FA_OPEN_EXISTING);
    if (fr != FR_OK) return fatResultToErrno(fr);

    FILE* out = std::fopen(destPath.c_str(), "wb");
    if (!out) {
        f_close(&file);
        return -errno;
    }

    const UINT bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    int rc = 0;
    while (true) {
        UINT br = 0;
        fr = f_read(&file, buf.data(), bufSize, &br);
        if (fr != FR_OK) {
            rc = fatResultToErrno(fr);
            break;
        }
        if (br == 0) break;
        if (std::fwrite(buf.data(), 1, br, out) != br) {
            rc = -EIO;
            break;
        }
    }

    std::fclose(out);
    FRESULT cfr = f_close(&file);
    if (rc == 0 && cfr != FR_OK)
        rc = fatResultToErrno(cfr);
    return rc;
}

int listExfat(HandleState& hs, const std::string& path, std::vector<std::string>& out) {
    const std::string p = normalizePath(path);
    struct exfat_node* node = nullptr;
    int rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    if (rc != 0) return rc;

    if (!(node->attrib & EXFAT_ATTRIB_DIR)) {
        exfat_put_node(&hs.exfatFs, node);
        return -ENOTDIR;
    }

    struct exfat_iterator it;
    rc = exfat_opendir(&hs.exfatFs, node, &it);
    if (rc != 0) {
        exfat_put_node(&hs.exfatFs, node);
        return rc;
    }
    struct exfat_node* child;
    while ((child = exfat_readdir(&it)) != nullptr) {
        char name[EXFAT_UTF8_NAME_BUFFER_MAX];
        exfat_get_name(child, name);
        std::string entry(name);
        if (child->attrib & EXFAT_ATTRIB_DIR)
            entry += "/";
        out.push_back(entry);
        exfat_put_node(&hs.exfatFs, child);
    }
    exfat_closedir(&hs.exfatFs, &it);
    exfat_put_node(&hs.exfatFs, node);
    return 0;
}

struct NtfsListCtx {
    std::vector<std::string>* out;
};

static int ntfs_filldir_cb(void* dirent, const ntfschar* name, const int name_len,
                           const int /*name_type*/, const s64 /*pos*/, const MFT_REF /*mref*/, const unsigned dt_type) {
    auto* ctx = static_cast<NtfsListCtx*>(dirent);
    if (!ctx || !ctx->out) return 0;
    char* utf8 = nullptr;
    int len = ntfs_ucstombs(name, name_len, &utf8, 0);
    if (len < 0 || !utf8) return 0;
    std::string entry(utf8, static_cast<size_t>(len));
    ntfs_free(utf8);
    if (entry == "." || entry == "..") return 0;
    if (dt_type == NTFS_DT_DIR)
        entry += "/";
    ctx->out->push_back(entry);
    return 0;
}

int listNtfs(HandleState& hs, const std::string& path, std::vector<std::string>& out) {
    if (!hs.ntfsVol) return -EINVAL;
    std::string p = normalizePath(path);
    ntfs_inode* ni = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, p.c_str());
    if (!ni) return -errno;

    if (!(ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
        ntfs_inode_close(ni);
        return -ENOTDIR;
    }

    s64 pos = 0;
    NtfsListCtx ctx{&out};
    int rc = ntfs_readdir(ni, &pos, &ctx, ntfs_filldir_cb);
    ntfs_inode_close(ni);
    if (rc != 0) return -errno;
    return 0;
}

int ensureExfatDirectoryPath(HandleState& hs, const std::string& parentPath) {
    if (!hs.exfatMounted) return -EINVAL;
    std::string normalized = normalizePath(parentPath);
    if (normalized == "/") return 0;

    std::string current = "/";
    size_t pos = 1;
    while (pos < normalized.size()) {
        size_t nextSlash = normalized.find('/', pos);
        std::string component = (nextSlash == std::string::npos)
                ? normalized.substr(pos)
                : normalized.substr(pos, nextSlash - pos);
        if (component.empty()) {
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }

        std::string candidate = (current == "/") ? ("/" + component) : (current + "/" + component);
        struct exfat_node* node = nullptr;
        int rc = exfat_lookup(&hs.exfatFs, &node, candidate.c_str());
        if (rc == 0) {
            bool isDir = (node->attrib & EXFAT_ATTRIB_DIR) != 0;
            exfat_put_node(&hs.exfatFs, node);
            if (!isDir) return -ENOTDIR;
            current = candidate;
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }
        if (rc != -ENOENT) return rc;
        rc = exfat_mkdir(&hs.exfatFs, candidate.c_str());
        if (rc != 0 && rc != -EEXIST) return rc;
        current = candidate;
        if (nextSlash == std::string::npos) break;
        pos = nextSlash + 1;
    }
    exfat_flush(&hs.exfatFs);
    return 0;
}

int mkdirExfatEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    if (!hs.exfatMounted) return -EINVAL;

    std::string parentPath;
    std::string dirName;
    if (!splitPath(path, parentPath, dirName)) return -EINVAL;
    const std::string p = normalizePath(path);

    struct exfat_node* node = nullptr;
    int rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    if (rc == 0) {
        exfat_put_node(&hs.exfatFs, node);
        return -EEXIST;
    }
    if (rc != -ENOENT) return rc;

    rc = ensureExfatDirectoryPath(hs, parentPath);
    if (rc != 0) return rc;

    rc = exfat_mkdir(&hs.exfatFs, p.c_str());
    if (rc != 0) return rc;
    exfat_flush(&hs.exfatFs);
    return 0;
}

int readExfatFile(HandleState& hs, const std::string& path, const std::string& destPath) {
    const std::string p = normalizePath(path);
    struct exfat_node* node = nullptr;
    int rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    if (rc != 0) return rc;

    if (node->attrib & EXFAT_ATTRIB_DIR) {
        exfat_put_node(&hs.exfatFs, node);
        return -EISDIR;
    }

    FILE* out = std::fopen(destPath.c_str(), "wb");
    if (!out) {
        exfat_put_node(&hs.exfatFs, node);
        return -errno;
    }

    const size_t bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    uint64_t remaining = node->size;
    off_t offset = 0;
    while (remaining > 0) {
        size_t chunk = remaining > bufSize ? bufSize : static_cast<size_t>(remaining);
        ssize_t n = exfat_generic_pread(&hs.exfatFs, node, buf.data(), chunk, offset);
        if (n < 0 || static_cast<size_t>(n) != chunk) {
            std::fclose(out);
            exfat_put_node(&hs.exfatFs, node);
            return -EIO;
        }
        if (std::fwrite(buf.data(), 1, chunk, out) != chunk) {
            std::fclose(out);
            exfat_put_node(&hs.exfatFs, node);
            return -EIO;
        }
        offset += n;
        remaining -= static_cast<size_t>(n);
    }

    std::fclose(out);
    exfat_put_node(&hs.exfatFs, node);
    return 0;
}

int readNtfsFile(HandleState& hs, const std::string& path, const std::string& destPath) {
    if (!hs.ntfsVol) return -EINVAL;
    std::string p = normalizePath(path);
    ntfs_inode* ni = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, p.c_str());
    if (!ni) return -errno;

    if (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) {
        ntfs_inode_close(ni);
        return -EISDIR;
    }

    ntfs_attr* na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        return -errno;
    }

    FILE* out = std::fopen(destPath.c_str(), "wb");
    if (!out) {
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        return -errno;
    }

    const size_t bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    s64 remaining = na->data_size;
    s64 offset = 0;
    while (remaining > 0) {
        s64 chunk = remaining > static_cast<s64>(bufSize) ? static_cast<s64>(bufSize) : remaining;
        s64 n = ntfs_attr_pread(na, offset, chunk, buf.data());
        if (n < 0) {
            std::fclose(out);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            return -EIO;
        }
        if (std::fwrite(buf.data(), 1, static_cast<size_t>(n), out) != static_cast<size_t>(n)) {
            std::fclose(out);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            return -EIO;
        }
        offset += n;
        remaining -= n;
        if (n == 0) break;
    }

    std::fclose(out);
    ntfs_attr_close(na);
    ntfs_inode_close(ni);
    return 0;
}

int flushVolumeBackingFile(HandleState& hs) {
    try {
        if (hs.volume) {
            auto file = hs.volume->GetFile();
            if (file) file->Flush();
        }
        return 0;
    } catch (...) {
        return -EIO;
    }
}

int ensureFatDirectoryPath(HandleState& hs, const std::string& parentPath) {
    if (!hs.fatMounted) return -EINVAL;
    std::string normalized = normalizePath(parentPath);
    if (normalized == "/") return 0;

    std::string current = "/";
    size_t pos = 1;
    while (pos < normalized.size()) {
        size_t nextSlash = normalized.find('/', pos);
        std::string component = (nextSlash == std::string::npos)
                ? normalized.substr(pos)
                : normalized.substr(pos, nextSlash - pos);
        if (component.empty()) {
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }

        std::string candidate = (current == "/") ? ("/" + component) : (current + "/" + component);
        std::string fatCandidate = fatDrivePath(hs.fatDrive, candidate);
        FILINFO info{};
        FRESULT fr = f_stat(fatCandidate.c_str(), &info);
        if (fr == FR_OK) {
            if (!(info.fattrib & AM_DIR)) return -ENOTDIR;
            current = candidate;
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }
        if (fr != FR_NO_FILE && fr != FR_NO_PATH)
            return fatResultToErrno(fr);

        fr = f_mkdir(fatCandidate.c_str());
        if (fr != FR_OK && fr != FR_EXIST)
            return fatResultToErrno(fr);
        current = candidate;
        if (nextSlash == std::string::npos) break;
        pos = nextSlash + 1;
    }
    return 0;
}

int writeFatFile(HandleState& hs, const std::string& path, const std::string& srcPath) {
    if (hs.readOnly) return -EROFS;
    if (!hs.fatMounted) return -EINVAL;

    std::string parentPath;
    std::string fileName;
    if (!splitPath(path, parentPath, fileName)) return -EINVAL;
    int ensureRc = ensureFatDirectoryPath(hs, parentPath);
    if (ensureRc != 0) return ensureRc;

    const std::string fatPath = fatDrivePath(hs.fatDrive, path);
    FILINFO info{};
    FRESULT fr = f_stat(fatPath.c_str(), &info);
    if (fr == FR_OK && (info.fattrib & AM_DIR))
        return -EISDIR;
    if (fr != FR_OK && fr != FR_NO_FILE)
        return fatResultToErrno(fr);

    FIL file{};
    fr = f_open(&file, fatPath.c_str(), FA_WRITE | FA_CREATE_ALWAYS);
    if (fr != FR_OK) return fatResultToErrno(fr);

    FILE* in = std::fopen(srcPath.c_str(), "rb");
    if (!in) {
        f_close(&file);
        return -errno;
    }

    const size_t bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    int rc = 0;

    while (true) {
        size_t nread = std::fread(buf.data(), 1, bufSize, in);
        if (nread == 0) {
            if (std::ferror(in)) rc = -EIO;
            break;
        }
        UINT bw = 0;
        fr = f_write(&file, buf.data(), static_cast<UINT>(nread), &bw);
        if (fr != FR_OK) {
            rc = fatResultToErrno(fr);
            break;
        }
        if (bw != nread) {
            rc = -ENOSPC;
            break;
        }
    }

    std::fclose(in);
    FRESULT sFr = f_sync(&file);
    FRESULT cFr = f_close(&file);
    if (rc == 0 && sFr != FR_OK) rc = fatResultToErrno(sFr);
    if (rc == 0 && cFr != FR_OK) rc = fatResultToErrno(cFr);
    if (rc != 0) return rc;
    return flushVolumeBackingFile(hs);
}

int mkdirFatEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    if (!hs.fatMounted) return -EINVAL;

    std::string parentPath;
    std::string dirName;
    if (!splitPath(path, parentPath, dirName)) return -EINVAL;
    int ensureRc = ensureFatDirectoryPath(hs, parentPath);
    if (ensureRc != 0) return ensureRc;

    const std::string fatPath = fatDrivePath(hs.fatDrive, path);
    FILINFO info{};
    FRESULT fr = f_stat(fatPath.c_str(), &info);
    if (fr == FR_OK) return -EEXIST;
    if (fr != FR_NO_FILE && fr != FR_NO_PATH) return fatResultToErrno(fr);

    fr = f_mkdir(fatPath.c_str());
    if (fr == FR_EXIST) return -EEXIST;
    if (fr != FR_OK) return fatResultToErrno(fr);
    return flushVolumeBackingFile(hs);
}

int deleteFatEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    if (!hs.fatMounted) return -EINVAL;
    FRESULT fr = f_unlink(fatDrivePath(hs.fatDrive, path).c_str());
    if (fr != FR_OK) return fatResultToErrno(fr);
    return flushVolumeBackingFile(hs);
}

int writeExfatFile(HandleState& hs, const std::string& path, const std::string& srcPath) {
    if (hs.readOnly) return -EROFS;
    const std::string p = normalizePath(path);
    struct exfat_node* node = nullptr;
    int rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    if (rc == -ENOENT) {
        rc = exfat_mknod(&hs.exfatFs, p.c_str());
        if (rc != 0) return rc;
        rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    }
    if (rc != 0) return rc;

    if (node->attrib & EXFAT_ATTRIB_DIR) {
        exfat_put_node(&hs.exfatFs, node);
        return -EISDIR;
    }

    FILE* in = std::fopen(srcPath.c_str(), "rb");
    if (!in) {
        exfat_put_node(&hs.exfatFs, node);
        return -errno;
    }

    struct stat st;
    if (fstat(fileno(in), &st) != 0) {
        std::fclose(in);
        exfat_put_node(&hs.exfatFs, node);
        return -errno;
    }
    uint64_t totalSize = static_cast<uint64_t>(st.st_size);
    rc = exfat_truncate(&hs.exfatFs, node, 0, true);
    if (rc != 0) {
        std::fclose(in);
        exfat_put_node(&hs.exfatFs, node);
        return rc;
    }

    const size_t bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    off_t offset = 0;
    size_t nread;
    while ((nread = std::fread(buf.data(), 1, bufSize, in)) > 0) {
        ssize_t n = exfat_generic_pwrite(&hs.exfatFs, node, buf.data(), nread, offset);
        if (n < 0 || static_cast<size_t>(n) != nread) {
            std::fclose(in);
            exfat_put_node(&hs.exfatFs, node);
            return -EIO;
        }
        offset += n;
    }

    std::fclose(in);
    rc = exfat_truncate(&hs.exfatFs, node, totalSize, false);
    if (rc != 0) {
        exfat_put_node(&hs.exfatFs, node);
        return rc;
    }
    exfat_flush(&hs.exfatFs);
    exfat_put_node(&hs.exfatFs, node);
    return 0;
}

int ensureNtfsDirectoryPath(HandleState& hs, const std::string& parentPath) {
    if (!hs.ntfsVol) return -EINVAL;
    std::string normalized = normalizePath(parentPath);
    if (normalized == "/") return 0;

    std::string current = "/";
    size_t pos = 1;
    while (pos < normalized.size()) {
        size_t nextSlash = normalized.find('/', pos);
        std::string component = (nextSlash == std::string::npos)
                ? normalized.substr(pos)
                : normalized.substr(pos, nextSlash - pos);
        if (component.empty()) {
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }

        std::string candidate = (current == "/") ? ("/" + component) : (current + "/" + component);
        ntfs_inode* candidateInode = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, candidate.c_str());
        if (candidateInode) {
            bool isDir = (candidateInode->mrec->flags & MFT_RECORD_IS_DIRECTORY) != 0;
            ntfs_inode_close(candidateInode);
            if (!isDir) return -ENOTDIR;
            current = candidate;
            if (nextSlash == std::string::npos) break;
            pos = nextSlash + 1;
            continue;
        }
        if (errno != ENOENT) {
            LOGE("ensureNtfsDirectoryPath: lookup failed path=%s errno=%d", candidate.c_str(), errno);
            return -errno;
        }

        ntfs_inode* parentInode = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, current.c_str());
        if (!parentInode) {
            LOGE("ensureNtfsDirectoryPath: parent lookup failed path=%s errno=%d", current.c_str(), errno);
            return -errno;
        }
        if (!(parentInode->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
            LOGE("ensureNtfsDirectoryPath: parent not dir path=%s", current.c_str());
            ntfs_inode_close(parentInode);
            return -ENOTDIR;
        }

        ntfschar* ntfsName = nullptr;
        int ntfsNameLen = ntfs_mbstoucs(component.c_str(), &ntfsName);
        if (ntfsNameLen <= 0 || ntfsNameLen > 255) {
            LOGE("ensureNtfsDirectoryPath: name conversion failed component=%s len=%d", component.c_str(), ntfsNameLen);
            ntfs_inode_close(parentInode);
            if (ntfsName) ntfs_ucsfree(ntfsName);
            return -EINVAL;
        }

        ntfs_inode* created = ntfs_create(parentInode, const_cpu_to_le32(0), ntfsName, static_cast<u8>(ntfsNameLen), S_IFDIR);
        int createErr = errno;
        ntfs_ucsfree(ntfsName);
        ntfs_inode_close(parentInode);
        if (!created) {
            LOGE("ensureNtfsDirectoryPath: create dir failed path=%s errno=%d", candidate.c_str(), createErr);
            return createErr > 0 ? -createErr : -EIO;
        }
        if (ntfs_inode_sync(created) != 0) {
            LOGE("ensureNtfsDirectoryPath: sync dir warning path=%s errno=%d", candidate.c_str(), errno);
        }
        ntfs_inode_close(created);
        current = candidate;
        if (nextSlash == std::string::npos) break;
        pos = nextSlash + 1;
    }

    if (hs.ntfsVol->dev && ntfs_device_sync(hs.ntfsVol->dev) != 0)
        LOGE("ensureNtfsDirectoryPath: device sync warning path=%s errno=%d", normalized.c_str(), errno);
    int flushRc = flushVolumeBackingFile(hs);
    if (flushRc != 0) {
        LOGE("ensureNtfsDirectoryPath: backing flush failed path=%s rc=%d", normalized.c_str(), flushRc);
        return flushRc;
    }
    return 0;
}

int mkdirNtfsEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    if (!hs.ntfsVol) return -EINVAL;
    ntfs_set_locale();
    ntfs_set_char_encoding("UTF-8");

    std::string normalizedPath = normalizePath(path);
    if (normalizedPath == "/") return -EINVAL;
    ntfs_inode* existing = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, normalizedPath.c_str());
    if (existing) {
        ntfs_inode_close(existing);
        return -EEXIST;
    }
    if (errno != ENOENT) return -errno;
    return ensureNtfsDirectoryPath(hs, normalizedPath);
}

int writeNtfsFile(HandleState& hs, const std::string& path, const std::string& srcPath) {
    if (hs.readOnly) return -EROFS;
    if (!hs.ntfsVol) return -EINVAL;
    ntfs_set_locale();
    ntfs_set_char_encoding("UTF-8");

    std::string parentPath;
    std::string fileName;
    if (!splitPath(path, parentPath, fileName)) return -EINVAL;
    std::string normalizedPath = normalizePath(path);
    int ensureRc = ensureNtfsDirectoryPath(hs, parentPath);
    if (ensureRc != 0) {
        LOGE("writeNtfsFile: ensure parent path failed parent=%s rc=%d", parentPath.c_str(), ensureRc);
        return ensureRc;
    }

    ntfs_inode* dirNi = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, parentPath.c_str());
    if (!dirNi) {
        LOGE("writeNtfsFile: parent lookup failed parent=%s errno=%d", parentPath.c_str(), errno);
        return -errno;
    }
    if (!(dirNi->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
        LOGE("writeNtfsFile: parent not directory parent=%s", parentPath.c_str());
        ntfs_inode_close(dirNi);
        return -ENOTDIR;
    }

    ntfschar* ntfsName = nullptr;
    int ntfsNameLen = ntfs_mbstoucs(fileName.c_str(), &ntfsName);
    if (ntfsNameLen <= 0 || ntfsNameLen > 255) {
        LOGE("writeNtfsFile: file name conversion failed name=%s len=%d", fileName.c_str(), ntfsNameLen);
        ntfs_inode_close(dirNi);
        if (ntfsName) ntfs_ucsfree(ntfsName);
        return -EINVAL;
    }

    ntfs_inode* ni = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, normalizedPath.c_str());
    if (!ni) {
        if (errno != ENOENT) {
            int rc = -errno;
            LOGE("writeNtfsFile: file lookup failed path=%s errno=%d", normalizedPath.c_str(), errno);
            ntfs_inode_close(dirNi);
            ntfs_ucsfree(ntfsName);
            return rc;
        }
        ni = ntfs_create(dirNi, const_cpu_to_le32(0), ntfsName, static_cast<u8>(ntfsNameLen), S_IFREG);
        if (!ni) {
            int rc = -errno;
            LOGE("writeNtfsFile: create file failed path=%s errno=%d", normalizedPath.c_str(), errno);
            ntfs_inode_close(dirNi);
            ntfs_ucsfree(ntfsName);
            return rc;
        }
    } else if (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) {
        ntfs_inode_close(ni);
        ntfs_inode_close(dirNi);
        ntfs_ucsfree(ntfsName);
        return -EISDIR;
    }

    ntfs_attr* na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        int rc = -errno;
        LOGE("writeNtfsFile: attr open failed path=%s errno=%d", normalizedPath.c_str(), errno);
        ntfs_inode_close(ni);
        ntfs_inode_close(dirNi);
        ntfs_ucsfree(ntfsName);
        return rc;
    }

    if (ntfs_attr_truncate(na, 0) != 0) {
        int rc = -errno;
        LOGE("writeNtfsFile: truncate(0) failed path=%s errno=%d", normalizedPath.c_str(), errno);
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        ntfs_inode_close(dirNi);
        ntfs_ucsfree(ntfsName);
        return rc;
    }

    FILE* in = std::fopen(srcPath.c_str(), "rb");
    if (!in) {
        int rc = -errno;
        LOGE("writeNtfsFile: fopen src failed path=%s src=%s errno=%d", normalizedPath.c_str(), srcPath.c_str(), errno);
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        ntfs_inode_close(dirNi);
        ntfs_ucsfree(ntfsName);
        return rc;
    }

    const size_t bufSize = 256 * 1024;
    std::vector<uint8_t> buf(bufSize);
    s64 offset = 0;
    int rc = 0;

    while (true) {
        size_t nread = std::fread(buf.data(), 1, bufSize, in);
        if (nread == 0) {
            if (std::ferror(in)) rc = -EIO;
            break;
        }
        s64 written = ntfs_attr_pwrite(na, offset, static_cast<s64>(nread), buf.data());
        if (written != static_cast<s64>(nread)) {
            rc = written < 0 ? -errno : -EIO;
            LOGE("writeNtfsFile: pwrite failed path=%s requested=%zu written=%lld errno=%d rc=%d",
                 normalizedPath.c_str(), nread, static_cast<long long>(written), errno, rc);
            break;
        }
        offset += written;
    }

    std::fclose(in);
    if (rc == 0 && ntfs_attr_truncate(na, offset) != 0)
        rc = -errno;
    if (rc != 0) {
        LOGE("writeNtfsFile: final truncate failed path=%s errno=%d rc=%d", normalizedPath.c_str(), errno, rc);
    }
    ntfs_attr_close(na);
    if (rc == 0 && ntfs_inode_sync(ni) != 0) {
        LOGE("writeNtfsFile: inode sync warning path=%s errno=%d", normalizedPath.c_str(), errno);
    }
    ntfs_inode_close(ni);
    ntfs_inode_close(dirNi);
    ntfs_ucsfree(ntfsName);

    if (rc == 0 && hs.ntfsVol->dev && ntfs_device_sync(hs.ntfsVol->dev) != 0) {
        LOGE("writeNtfsFile: device sync warning path=%s errno=%d", normalizedPath.c_str(), errno);
    }
    if (rc == 0) {
        int flushRc = flushVolumeBackingFile(hs);
        if (flushRc != 0) {
            rc = flushRc;
            LOGE("writeNtfsFile: backing flush failed path=%s rc=%d", normalizedPath.c_str(), rc);
        }
    }
    return rc;
}

int deleteExfatEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    const std::string p = normalizePath(path);
    struct exfat_node* node = nullptr;
    int rc = exfat_lookup(&hs.exfatFs, &node, p.c_str());
    if (rc != 0) return rc;

    if (node->attrib & EXFAT_ATTRIB_DIR)
        rc = exfat_rmdir(&hs.exfatFs, node);
    else
        rc = exfat_unlink(&hs.exfatFs, node);

    exfat_put_node(&hs.exfatFs, node);
    exfat_flush(&hs.exfatFs);
    return rc;
}

int deleteNtfsEntry(HandleState& hs, const std::string& path) {
    if (hs.readOnly) return -EROFS;
    if (!hs.ntfsVol) return -EINVAL;
    ntfs_set_locale();
    ntfs_set_char_encoding("UTF-8");

    std::string parentPath;
    std::string name;
    if (!splitPath(path, parentPath, name)) return -EINVAL;
    std::string normalizedPath = normalizePath(path);

    ntfs_inode* dirNi = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, parentPath.c_str());
    if (!dirNi) return -errno;
    if (!(dirNi->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
        ntfs_inode_close(dirNi);
        return -ENOTDIR;
    }

    ntfschar* ntfsName = nullptr;
    int ntfsNameLen = ntfs_mbstoucs(name.c_str(), &ntfsName);
    if (ntfsNameLen <= 0 || ntfsNameLen > 255) {
        ntfs_inode_close(dirNi);
        if (ntfsName) ntfs_ucsfree(ntfsName);
        return -EINVAL;
    }

    ntfs_inode* ni = ntfs_pathname_to_inode(hs.ntfsVol, nullptr, normalizedPath.c_str());
    if (!ni) {
        int rc = -errno;
        ntfs_inode_close(dirNi);
        ntfs_ucsfree(ntfsName);
        return rc;
    }

    int rc = ntfs_delete(hs.ntfsVol, normalizedPath.c_str(), ni, dirNi, ntfsName, static_cast<u8>(ntfsNameLen));
    ntfs_ucsfree(ntfsName);
    if (rc != 0)
        return -errno;
    if (hs.ntfsVol->dev && ntfs_device_sync(hs.ntfsVol->dev) != 0)
        LOGE("deleteNtfsEntry: device sync warning path=%s errno=%d", normalizedPath.c_str(), errno);
    int flushRc = flushVolumeBackingFile(hs);
    if (flushRc != 0) return flushRc;
    return 0;
}

jlong openVolumeHandleFromFd(int fd,
                             const std::vector<uint8_t>& pwdBytes,
                             int pim,
                             bool hidden,
                             bool readOnly,
                             const std::vector<std::string>& keyfilePaths,
                             const std::vector<uint8_t>& protectionPwdBytes,
                             int protectionPim) {
    if (fd < 0) return 0;
    if (throwIfCanceled()) return cancelRc();
    FdGuard fdGuard(fd);
    auto pwd = make_shared<VeraCrypt::VolumePassword>(pwdBytes.data(), pwdBytes.size());
    pwd = applyKeyfilesToPassword(pwd, keyfilePaths);
    if (!pwd || pwd->Size() < 1) return 0;
    shared_ptr<VeraCrypt::VolumePassword> protectionPwd;
    VeraCrypt::VolumeProtection::Enum protection = VeraCrypt::VolumeProtection::None;
    int effectiveProtectionPim = protectionPim > 0 ? protectionPim : 0;
    if (readOnly) {
        protection = VeraCrypt::VolumeProtection::ReadOnly;
    } else if (!hidden && !protectionPwdBytes.empty()) {
        protectionPwd = make_shared<VeraCrypt::VolumePassword>(protectionPwdBytes.data(), protectionPwdBytes.size());
        if (!protectionPwd || protectionPwd->Size() < 1) return 0;
        protection = VeraCrypt::VolumeProtection::HiddenVolumeReadOnly;
    }
    auto file = make_shared<VeraCrypt::File>();
    file->AssignSystemHandle(fdGuard.release(), false);

    auto volume = make_shared<VeraCrypt::Volume>();
    VeraCrypt::VolumeType::Enum vtype = hidden ? VeraCrypt::VolumeType::Hidden : VeraCrypt::VolumeType::Normal;
    volume->Open(file, pwd, pim, shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(), false,
                 protection, protectionPwd, effectiveProtectionPim,
                 shared_ptr<VeraCrypt::Pkcs5Kdf>(), shared_ptr<VeraCrypt::KeyfileList>(),
                 vtype, false, false);
    if (throwIfCanceled()) return cancelRc();

    auto state = std::make_unique<HandleState>();
    state->hidden = hidden;
    state->readOnly = readOnly;
    state->volume = volume;
    state->file = file;
    state->fs = detectFs(volume);

    if (state->fs == FsType::ExFat) {
        if (!mountExfat(*state)) {
            if (isCancelRequested() || errno == ECANCELED) return cancelRc();
            LOGE("exFAT mount failed");
            return 0;
        }
    } else if (state->fs == FsType::Ntfs) {
        int ntfsStatus = NTFS_VOLUME_OK;
        if (!mountNtfs(*state, &ntfsStatus)) {
            if (isCancelRequested() || errno == ECANCELED) return cancelRc();
            const bool unsafeState = (ntfsStatus == NTFS_VOLUME_HIBERNATED ||
                                      ntfsStatus == NTFS_VOLUME_UNCLEAN_UNMOUNT);
            if (!state->readOnly && unsafeState) {
                int fallbackReason = ntfsStatus;
                state->readOnly = true;
                if (!mountNtfs(*state, &ntfsStatus)) {
                    if (isCancelRequested() || errno == ECANCELED) return cancelRc();
                    LOGE("NTFS mount failed after read-only fallback");
                    return 0;
                }
                if (fallbackReason == NTFS_VOLUME_HIBERNATED) {
                    state->mountWarning = VC_MOUNT_WARNING_NTFS_HIBERNATED_READONLY;
                } else if (fallbackReason == NTFS_VOLUME_UNCLEAN_UNMOUNT) {
                    state->mountWarning = VC_MOUNT_WARNING_NTFS_UNCLEAN_READONLY;
                }
            } else {
                LOGE("NTFS mount failed");
                return 0;
            }
        }
    } else if (state->fs == FsType::Fat) {
        if (!mountFat(*state)) {
            if (isCancelRequested() || errno == ECANCELED) return cancelRc();
            LOGE("FAT mount failed");
            return 0;
        }
    } else {
        if (isCancelRequested()) return cancelRc();
        LOGE("Unknown filesystem in container");
        return 0;
    }

    long h = addHandle(std::move(state));
    LOGI("vcOpenFd: handle %ld", h);
    return static_cast<jlong>(h);
}
} // namespace

int vcFatRegisterDrive(VcBlockDevice* dev) {
    if (!dev) return -EINVAL;
    std::lock_guard<std::mutex> lock(g_fatDriveMutex);
    for (size_t i = 0; i < g_fatDrives.size(); ++i) {
        if (g_fatDrives[i] == nullptr) {
            g_fatDrives[i] = dev;
            return static_cast<int>(i);
        }
    }
    return -EMFILE;
}

void vcFatReleaseDrive(BYTE pdrv) {
    std::lock_guard<std::mutex> lock(g_fatDriveMutex);
    if (pdrv < g_fatDrives.size())
        g_fatDrives[pdrv] = nullptr;
}

VcBlockDevice* vcFatGetDrive(BYTE pdrv) {
    std::lock_guard<std::mutex> lock(g_fatDriveMutex);
    if (pdrv >= g_fatDrives.size()) return nullptr;
    return g_fatDrives[pdrv];
}

static DRESULT vcFatReadBlocks(VcBlockDevice* dev, BYTE* buff, LBA_t sector, UINT count) {
    if (!dev || !dev->volume || !buff || count == 0) return RES_PARERR;
    if (isCancelRequested()) return RES_ERROR;
    uint64_t offset = static_cast<uint64_t>(sector) * static_cast<uint64_t>(dev->sectorSize);
    size_t bytes = static_cast<size_t>(count) * static_cast<size_t>(dev->sectorSize);
    if (offset + bytes > dev->size) return RES_PARERR;
    try {
        VeraCrypt::BufferPtr buf(buff, bytes);
        dev->volume->ReadSectors(buf, offset);
        return RES_OK;
    } catch (...) {
        return RES_ERROR;
    }
}

static DRESULT vcFatWriteBlocks(VcBlockDevice* dev, const BYTE* buff, LBA_t sector, UINT count) {
    if (!dev || !dev->volume || !buff || count == 0) return RES_PARERR;
    if (dev->readOnly) return RES_WRPRT;
    if (isCancelRequested()) return RES_ERROR;
    uint64_t offset = static_cast<uint64_t>(sector) * static_cast<uint64_t>(dev->sectorSize);
    size_t bytes = static_cast<size_t>(count) * static_cast<size_t>(dev->sectorSize);
    if (offset + bytes > dev->size) return RES_PARERR;
    try {
        VeraCrypt::ConstBufferPtr buf(buff, bytes);
        dev->volume->WriteSectors(buf, offset);
        return RES_OK;
    } catch (...) {
        return RES_ERROR;
    }
}

extern "C" DSTATUS disk_status(BYTE pdrv) {
    VcBlockDevice* dev = vcFatGetDrive(pdrv);
    if (!dev || !dev->volume) return STA_NOINIT;
    return dev->readOnly ? STA_PROTECT : 0;
}

extern "C" DSTATUS disk_initialize(BYTE pdrv) {
    return disk_status(pdrv);
}

extern "C" DRESULT disk_read(BYTE pdrv, BYTE* buff, LBA_t sector, UINT count) {
    return vcFatReadBlocks(vcFatGetDrive(pdrv), buff, sector, count);
}

#if FF_FS_READONLY == 0
extern "C" DRESULT disk_write(BYTE pdrv, const BYTE* buff, LBA_t sector, UINT count) {
    return vcFatWriteBlocks(vcFatGetDrive(pdrv), buff, sector, count);
}
#endif

extern "C" DRESULT disk_ioctl(BYTE pdrv, BYTE cmd, void* buff) {
    VcBlockDevice* dev = vcFatGetDrive(pdrv);
    if (!dev || !dev->volume) return RES_NOTRDY;
    switch (cmd) {
        case CTRL_SYNC:
            try {
                if (auto file = dev->volume->GetFile()) file->Flush();
                return RES_OK;
            } catch (...) {
                return RES_ERROR;
            }
        case GET_SECTOR_COUNT:
            if (!buff) return RES_PARERR;
            *static_cast<LBA_t*>(buff) = static_cast<LBA_t>(dev->size / dev->sectorSize);
            return RES_OK;
        case GET_SECTOR_SIZE:
            if (!buff) return RES_PARERR;
            *static_cast<WORD*>(buff) = static_cast<WORD>(dev->sectorSize);
            return RES_OK;
        case GET_BLOCK_SIZE:
            if (!buff) return RES_PARERR;
            *static_cast<DWORD*>(buff) = 1;
            return RES_OK;
        default:
            return RES_PARERR;
    }
}

extern "C" int vcShouldCancelCurrentOperation() {
    return isCancelRequested() ? 1 : 0;
}

extern "C" {

JNIEXPORT void JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcRequestCancel(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancelRequested.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcClearCancel(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancelRequested.store(false, std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcOpen(
        JNIEnv *env, jobject /*thiz*/, jstring containerPath, jbyteArray password, jint pim, jboolean hidden,
        jobjectArray keyfilePaths,
        jbyteArray protectionPassword, jint protectionPim) {
    const std::string path = jstringToUtf8(env, containerPath);
    if (path.empty()) return 0;
    int fd = ::open(path.c_str(), O_RDWR);
    bool readOnly = false;
    if (fd < 0) {
        fd = ::open(path.c_str(), O_RDONLY);
        readOnly = true;
    }
    if (fd < 0) return 0;
    auto pwd = jbytes(env, password);
    auto keyfiles = jstrings(env, keyfilePaths);
    auto protectionPwd = jbytes(env, protectionPassword);
    jlong out = 0;
    try {
        out = openVolumeHandleFromFd(fd, pwd, pim, hidden, readOnly, keyfiles, protectionPwd, protectionPim);
    } catch (const VeraCrypt::SystemException& e) {
        if (e.GetErrorCode() == ECANCELED || isCancelRequested()) {
            LOGI("vcOpen canceled");
            out = -ECANCELED;
        } else {
            LOGE("vcOpen system error: %s (errno=%lld)", e.what(), static_cast<long long>(e.GetErrorCode()));
        }
    } catch (const VeraCrypt::ProtectionPasswordIncorrect& e) {
        LOGE("vcOpen protection password error: %s", e.what());
        out = VC_ERR_OPEN_PROTECTION_PASSWORD;
    } catch (const VeraCrypt::PasswordException& e) {
        LOGE("vcOpen password error: %s", e.what());
        out = VC_ERR_OPEN_PASSWORD;
    } catch (const VeraCrypt::Exception& e) {
        LOGE("vcOpen error: %s", e.what());
    } catch (const std::exception& e) {
        LOGE("vcOpen std error: %s", e.what());
    } catch (...) {
        LOGE("vcOpen unknown error");
    }
    std::fill(pwd.begin(), pwd.end(), 0);
    std::fill(protectionPwd.begin(), protectionPwd.end(), 0);
    return out;
}

JNIEXPORT jlong JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcOpenFd(
        JNIEnv *env, jobject /*thiz*/, jint fd, jbyteArray password, jint pim, jboolean hidden, jboolean readOnly,
        jobjectArray keyfilePaths,
        jbyteArray protectionPassword, jint protectionPim) {
    auto pwdBytes = jbytes(env, password);
    auto keyfiles = jstrings(env, keyfilePaths);
    auto protectionPwd = jbytes(env, protectionPassword);
    jlong out = 0;
    try {
        out = openVolumeHandleFromFd(fd, pwdBytes, pim, hidden, readOnly, keyfiles, protectionPwd, protectionPim);
    } catch (const VeraCrypt::SystemException& e) {
        if (e.GetErrorCode() == ECANCELED || isCancelRequested()) {
            LOGI("vcOpenFd canceled");
            out = -ECANCELED;
        } else {
            LOGE("vcOpenFd system error: %s (errno=%lld)", e.what(), static_cast<long long>(e.GetErrorCode()));
        }
    } catch (const VeraCrypt::ProtectionPasswordIncorrect& e) {
        LOGE("vcOpenFd protection password error: %s", e.what());
        out = VC_ERR_OPEN_PROTECTION_PASSWORD;
    } catch (const VeraCrypt::PasswordException& e) {
        LOGE("vcOpenFd password error: %s", e.what());
        out = VC_ERR_OPEN_PASSWORD;
    } catch (const VeraCrypt::Exception& e) {
        LOGE("vcOpenFd error: %s", e.what());
    } catch (const std::exception& e) {
        LOGE("vcOpenFd std error: %s", e.what());
    } catch (...) {
        LOGE("vcOpenFd unknown error");
    }
    std::fill(pwdBytes.begin(), pwdBytes.end(), 0);
    std::fill(protectionPwd.begin(), protectionPwd.end(), 0);
    return out;
}

JNIEXPORT void JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcClose(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    HandleState* hs = getHandle(handle);
    if (hs) {
        if (hs->exfatMounted) {
            exfat_unmount(&hs->exfatFs);
            hs->exfatMounted = false;
        }
        if (hs->fatMounted) {
            f_mount(nullptr, fatDrivePath(hs->fatDrive).c_str(), 0);
            hs->fatMounted = false;
        }
        if (hs->fatDrive != 0xFF) {
            vcFatReleaseDrive(hs->fatDrive);
            hs->fatDrive = 0xFF;
        }
        if (hs->ntfsMounted && hs->ntfsVol) {
            ntfs_umount(hs->ntfsVol, false);
            hs->ntfsVol = nullptr;
            hs->ntfsDev = nullptr;
            hs->ntfsMounted = false;
        }
        hs->blockDev.reset();
        hs->volume.reset();
        hs->file.reset();
    }
    dropHandle(handle);
    LOGI("vcClose: handle %ld", handle);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcList(JNIEnv *env, jobject /*thiz*/, jlong handle, jstring path) {
    HandleState* hs = getHandle(handle);
    if (!hs) {
        jclass strCls = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, strCls, nullptr);
    }
    std::vector<std::string> entries;
    std::string p = jstringToUtf8(env, path);
    int rc = -ENOTSUP;
    if (hs->fs == FsType::ExFat && hs->exfatMounted) {
        rc = listExfat(*hs, p, entries);
    } else if (hs->fs == FsType::Fat && hs->fatMounted) {
        rc = listFat(*hs, p, entries);
    } else if (hs->fs == FsType::Ntfs && hs->ntfsMounted) {
        rc = listNtfs(*hs, p, entries);
    }
    if (rc != 0) {
        LOGE("vcList failed: %d", rc);
        entries.clear();
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(entries.size()), strCls, nullptr);
    for (size_t i = 0; i < entries.size(); ++i) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(entries[i].c_str()));
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcReadFile(JNIEnv *env, jobject /*thiz*/, jlong handle, jstring path, jstring destPath) {
    HandleState* hs = getHandle(handle);
    if (!hs) return -EINVAL;
    std::string p = jstringToUtf8(env, path);
    std::string dest = jstringToUtf8(env, destPath);
    if (hs->fs == FsType::ExFat && hs->exfatMounted)
        return readExfatFile(*hs, p, dest);
    if (hs->fs == FsType::Fat && hs->fatMounted)
        return readFatFile(*hs, p, dest);
    if (hs->fs == FsType::Ntfs && hs->ntfsMounted)
        return readNtfsFile(*hs, p, dest);
    return -ENOTSUP;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcWriteFile(JNIEnv *env, jobject /*thiz*/, jlong handle, jstring path, jstring srcPath) {
    HandleState* hs = getHandle(handle);
    if (!hs) return -EINVAL;
    std::string p = jstringToUtf8(env, path);
    std::string src = jstringToUtf8(env, srcPath);
    if (hs->fs == FsType::ExFat && hs->exfatMounted)
        return writeExfatFile(*hs, p, src);
    if (hs->fs == FsType::Fat && hs->fatMounted)
        return writeFatFile(*hs, p, src);
    if (hs->fs == FsType::Ntfs && hs->ntfsMounted)
        return writeNtfsFile(*hs, p, src);
    return -ENOTSUP;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcMkdir(JNIEnv *env, jobject /*thiz*/, jlong handle, jstring path) {
    HandleState* hs = getHandle(handle);
    if (!hs) return -EINVAL;
    std::string p = jstringToUtf8(env, path);
    if (hs->fs == FsType::ExFat && hs->exfatMounted)
        return mkdirExfatEntry(*hs, p);
    if (hs->fs == FsType::Fat && hs->fatMounted)
        return mkdirFatEntry(*hs, p);
    if (hs->fs == FsType::Ntfs && hs->ntfsMounted)
        return mkdirNtfsEntry(*hs, p);
    return -ENOTSUP;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcDelete(JNIEnv *env, jobject /*thiz*/, jlong handle, jstring path) {
    HandleState* hs = getHandle(handle);
    if (!hs) return -EINVAL;
    std::string p = jstringToUtf8(env, path);
    if (hs->fs == FsType::ExFat && hs->exfatMounted)
        return deleteExfatEntry(*hs, p);
    if (hs->fs == FsType::Fat && hs->fatMounted)
        return deleteFatEntry(*hs, p);
    if (hs->fs == FsType::Ntfs && hs->ntfsMounted)
        return deleteNtfsEntry(*hs, p);
    return -ENOTSUP;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcGetFsType(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    HandleState* hs = getHandle(handle);
    if (!hs) return 0;
    switch (hs->fs) {
        case FsType::ExFat: return 1;
        case FsType::Ntfs: return 2;
        case FsType::Fat: return 3;
        default: return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcIsReadOnly(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    HandleState* hs = getHandle(handle);
    if (!hs) return JNI_FALSE;
    return hs->readOnly ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcGetMountWarning(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    (void)env;
    HandleState* hs = getHandle(handle);
    if (!hs) return VC_MOUNT_WARNING_NONE;
    return hs->mountWarning;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcCreateVolume(
        JNIEnv *env, jobject /*thiz*/, jstring containerPath, jlong sizeBytes,
        jstring filesystem, jstring algorithm, jstring hash, jint pim,
        jbyteArray password, jobjectArray keyfilePaths, jlong hiddenSizeBytes,
        jbyteArray hiddenPassword, jobjectArray hiddenKeyfilePaths, jint hiddenPim, jboolean /*readOnly*/) {
    std::string path = jstringToUtf8(env, containerPath);
    std::string fs = toLowerAscii(jstringToUtf8(env, filesystem));
    std::string algo = jstringToUtf8(env, algorithm);
    std::string kdfHash = jstringToUtf8(env, hash);
    auto pwd = jbytes(env, password);
    auto keyfiles = jstrings(env, keyfilePaths);
    auto hiddenPwd = jbytes(env, hiddenPassword);
    auto hiddenKeyfiles = jstrings(env, hiddenKeyfilePaths);

    if (path.empty()) return -EINVAL;
    if (fs != "exfat" && fs != "ntfs" && fs != "fat") return -ENOTSUP;

    int fd = ::open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    if (fd < 0) return -errno;
    int rc = createVolumeOnFd(fd, static_cast<uint64_t>(sizeBytes), fs, algo, kdfHash, pim,
                              pwd, keyfiles, static_cast<uint64_t>(hiddenSizeBytes), hiddenPwd,
                              hiddenKeyfiles, hiddenPim);
    std::fill(pwd.begin(), pwd.end(), 0);
    std::fill(hiddenPwd.begin(), hiddenPwd.end(), 0);
    return rc;
}

JNIEXPORT jint JNICALL
Java_dev_alsatianconsulting_cryptocontainer_jni_CryptoNative_vcCreateVolumeFd(
        JNIEnv *env, jobject /*thiz*/, jint fd, jlong sizeBytes,
        jstring filesystem, jstring algorithm, jstring hash, jint pim,
        jbyteArray password, jobjectArray keyfilePaths, jlong hiddenSizeBytes,
        jbyteArray hiddenPassword, jobjectArray hiddenKeyfilePaths, jint hiddenPim, jboolean /*readOnly*/) {
    std::string fs = toLowerAscii(jstringToUtf8(env, filesystem));
    std::string algo = jstringToUtf8(env, algorithm);
    std::string kdfHash = jstringToUtf8(env, hash);
    auto pwd = jbytes(env, password);
    auto keyfiles = jstrings(env, keyfilePaths);
    auto hiddenPwd = jbytes(env, hiddenPassword);
    auto hiddenKeyfiles = jstrings(env, hiddenKeyfilePaths);
    if (fd < 0) return -EINVAL;
    if (fs != "exfat" && fs != "ntfs" && fs != "fat") return -ENOTSUP;
    int rc = createVolumeOnFd(fd, static_cast<uint64_t>(sizeBytes), fs, algo, kdfHash, pim,
                              pwd, keyfiles, static_cast<uint64_t>(hiddenSizeBytes), hiddenPwd,
                              hiddenKeyfiles, hiddenPim);
    std::fill(pwd.begin(), pwd.end(), 0);
    std::fill(hiddenPwd.begin(), hiddenPwd.end(), 0);
    return rc;
}

} // extern "C"
