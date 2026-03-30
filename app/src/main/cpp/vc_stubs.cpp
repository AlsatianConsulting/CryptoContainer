#include "vc_config.h"
#include "Common/Crypto.h"
#include "Common/EncryptionThreadPool.h"
#include "Platform/File.h"
#include "Volume/Crc32.h"
#include "Volume/Keyfile.h"
#include "Volume/VolumeException.h"

extern "C" {

void InitSecurityParameters() {}

void DumpFilterDriverControlFiles() {}

int UacElevated = 0;

void Randfreeze() {}

void EncryptionThreadPoolDoWork(EncryptionThreadPoolWorkType type,
                                uint8 *data,
                                const UINT64_STRUCT *startUnitNo,
                                uint32 unitCount,
                                PCRYPTO_INFO cryptoInfo)
{
    switch (type)
    {
        case EncryptDataUnitsWork:
            EncryptDataUnitsCurrentThread(data, startUnitNo,
                                          (TC_LARGEST_COMPILER_UINT) unitCount,
                                          cryptoInfo);
            break;
        case DecryptDataUnitsWork:
            DecryptDataUnitsCurrentThread(data, startUnitNo,
                                          (TC_LARGEST_COMPILER_UINT) unitCount,
                                          cryptoInfo);
            break;
        default:
            // No-op for unsupported work types in the Android port.
            break;
    }
}

}

namespace VeraCrypt
{
    bool Keyfile::HiddenFileWasPresentInKeyfilePath = false;

    shared_ptr<VolumePassword> Keyfile::ApplyListToPassword(shared_ptr<KeyfileList> keyfiles,
                                                            shared_ptr<VolumePassword> password,
                                                            bool)
    {
        if (!password)
            password = make_shared<VolumePassword>();

        if (!keyfiles || keyfiles->empty())
            return password;

        HiddenFileWasPresentInKeyfilePath = false;

        auto newPassword = make_shared<VolumePassword>();
        SecureBuffer keyfilePool(password->Size() <= VolumePassword::MaxLegacySize
            ? VolumePassword::MaxLegacySize
            : VolumePassword::MaxSize);
        keyfilePool.Zero();
        if (password->Size() > 0)
            keyfilePool.CopyFrom(ConstBufferPtr(password->DataPtr(), password->Size()));

        for (const auto& keyfile : *keyfiles)
        {
            if (!keyfile)
                continue;

            const FilesystemPath path(*keyfile);
            if (path.IsDirectory())
                throw ParameterIncorrect(SRC_POS);

            File file;
            file.Open(path, File::OpenRead, File::ShareRead);

            Crc32 crc32;
            size_t poolPos = 0;
            uint64 totalLength = 0;
            uint64 readLength = 0;
            SecureBuffer keyfileBuf(File::GetOptimalReadSize());

            while ((readLength = file.Read(keyfileBuf)) > 0)
            {
                for (size_t i = 0; i < static_cast<size_t>(readLength); ++i)
                {
                    uint32 crc = crc32.Process(keyfileBuf[i]);

                    keyfilePool[poolPos++] += static_cast<uint8>(crc >> 24);
                    if (poolPos >= keyfilePool.Size())
                        poolPos = 0;
                    keyfilePool[poolPos++] += static_cast<uint8>(crc >> 16);
                    if (poolPos >= keyfilePool.Size())
                        poolPos = 0;
                    keyfilePool[poolPos++] += static_cast<uint8>(crc >> 8);
                    if (poolPos >= keyfilePool.Size())
                        poolPos = 0;
                    keyfilePool[poolPos++] += static_cast<uint8>(crc);
                    if (poolPos >= keyfilePool.Size())
                        poolPos = 0;
                    if (++totalLength >= MaxProcessedLength)
                        goto keyfile_done;
                }
            }

keyfile_done:
            if (totalLength < MinProcessedLength)
                throw InsufficientData(SRC_POS, path);
        }

        newPassword->Set(keyfilePool.Ptr(), keyfilePool.Size());
        return newPassword;
    }
}
