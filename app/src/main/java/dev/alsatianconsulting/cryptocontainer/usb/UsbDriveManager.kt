package dev.alsatianconsulting.cryptocontainer.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import dev.alsatianconsulting.cryptocontainer.jni.CryptoNative
import dev.alsatianconsulting.cryptocontainer.util.charArrayToUtf8Bytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

const val ACTION_USB_PERMISSION = "dev.alsatianconsulting.cryptocontainer.USB_PERMISSION"

sealed interface UsbDriveState {
    object Idle : UsbDriveState
    // _permTick increments each time permission is granted, forcing Compose to reread
    // hasPermission even though device/name haven't changed.
    data class DeviceAttached(val device: UsbDevice, val name: String, val _permTick: Int = 0) : UsbDriveState
    object Unlocking : UsbDriveState
    data class Mounted(
        val device: UsbDevice,
        val handle: Long,
        val blockCount: Long,
        val blockSize: Int,
        val label: String
    ) : UsbDriveState
    data class Error(val message: String) : UsbDriveState
}

class UsbDriveManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<UsbDriveState>(UsbDriveState.Idle)
    val state: StateFlow<UsbDriveState> = _state

    private var massStorage: UsbMassStorage? = null
    private var currentHandle: Long = 0L

    // ── Device lifecycle ─────────────────────────────────────────────────────

    fun onDeviceAttached(device: UsbDevice) {
        val isMassStorage = (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
        }
        if (!isMassStorage) return
        _state.value = UsbDriveState.DeviceAttached(
            device = device,
            name   = device.productName ?: "USB Drive"
        )
        // Auto-request permission so the user only sees the OS dialog, not a button
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
        }
    }

    /** Call after the OS permission dialog grants access. Increments the tick so
     *  Compose recomposes and reads hasPermission() = true. */
    fun onPermissionGranted(device: UsbDevice) {
        val current = _state.value
        if (current is UsbDriveState.DeviceAttached && current.device == device) {
            _state.value = current.copy(_permTick = current._permTick + 1)
        } else {
            onDeviceAttached(device)
        }
    }

    fun onDeviceDetached(device: UsbDevice) {
        when (val s = _state.value) {
            is UsbDriveState.Mounted        -> if (s.device == device) closeInternal()
            is UsbDriveState.DeviceAttached -> if (s.device == device) _state.value = UsbDriveState.Idle
            else                            -> Unit
        }
    }

    /** Scan for USB mass storage devices already connected at startup. */
    fun checkExistingDevices() {
        usbManager.deviceList.values.forEach { onDeviceAttached(it) }
    }

    // ── Permission ───────────────────────────────────────────────────────────

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        // API 34+ forbids FLAG_MUTABLE with an *implicit* Intent.
        // UsbManager adds EXTRA_PERMISSION_GRANTED to the returned intent, so the
        // PendingIntent must remain mutable.  Making the Intent explicit (setPackage)
        // satisfies the Android 14 requirement while keeping mutability.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
            flags
        )
        usbManager.requestPermission(device, pi)
    }

    // ── Mount ────────────────────────────────────────────────────────────────

    suspend fun mount(
        device: UsbDevice,
        password: CharArray,
        pim: Int,
        hidden: Boolean,
        readOnly: Boolean
    ): Int = withContext(Dispatchers.IO) {
        _state.value = UsbDriveState.Unlocking

        val ms = UsbMassStorage.open(usbManager, device)
            ?: return@withContext run {
                _state.value = UsbDriveState.Error("Cannot open USB mass storage interface")
                -1
            }

        val passwordBytes = charArrayToUtf8Bytes(password)
        password.fill('\u0000')

        val handle = try {
            CryptoNative.vcOpenUsbDrive(
                sectorReader = ms,
                totalSectors = ms.blockCount,
                sectorSize   = ms.blockSize,
                password     = passwordBytes,
                pim          = pim,
                hidden       = hidden,
                readOnly     = readOnly
            )
        } finally {
            passwordBytes.fill(0)
        }

        if (handle <= 0L) {
            ms.close()
            _state.value = UsbDriveState.Error(
                when (handle) {
                    -1001L -> "Wrong password or not a VeraCrypt volume"
                    -1002L -> "Wrong hidden-volume protection password"
                    else   -> "Failed to open drive (code $handle)"
                }
            )
            return@withContext handle.toInt().coerceAtMost(-1)
        }

        massStorage   = ms
        currentHandle = handle
        _state.value  = UsbDriveState.Mounted(
            device     = device,
            handle     = handle,
            blockCount = ms.blockCount,
            blockSize  = ms.blockSize,
            label      = device.productName ?: "USB Drive"
        )
        0
    }

    // ── Filesystem operations ─────────────────────────────────────────────

    fun list(path: String): Array<String> =
        if (currentHandle > 0L) CryptoNative.vcList(currentHandle, path) else emptyArray()

    fun readFile(path: String, destPath: String): Int =
        if (currentHandle > 0L) CryptoNative.vcReadFile(currentHandle, path, destPath) else -1

    fun writeFile(path: String, srcPath: String): Int =
        if (currentHandle > 0L) CryptoNative.vcWriteFile(currentHandle, path, srcPath) else -1

    fun mkdir(path: String): Int =
        if (currentHandle > 0L) CryptoNative.vcMkdir(currentHandle, path) else -1

    fun delete(path: String): Int =
        if (currentHandle > 0L) CryptoNative.vcDelete(currentHandle, path) else -1

    // ── Unmount ───────────────────────────────────────────────────────────

    fun close() { closeInternal() }

    private fun closeInternal() {
        val h = currentHandle
        if (h != 0L) {
            currentHandle = 0L
            try { CryptoNative.vcClose(h) } catch (_: Throwable) {}
        }
        val ms = massStorage
        massStorage = null
        ms?.close()
        _state.value = UsbDriveState.Idle
        checkExistingDevices()
    }
}
