package dev.alsatianconsulting.cryptocontainer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

/**
 * Minimal USB Mass Storage Bulk-Only Transport (BOT) + SCSI READ(10)/WRITE(10) driver.
 * Provides sector-level read/write access to a USB drive without root.
 *
 * The [readSectors] and [writeSectors] methods are called via JNI from the native layer
 * (vcOpenUsbDrive) to provide sector I/O during VeraCrypt header parsing and filesystem access.
 */
class UsbMassStorage(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    val blockCount: Long,
    val blockSize: Int = 512,
    private val lun: Int = 0
) : AutoCloseable {

    private var cbwTag = 1

    companion object {
        private const val CBW_SIGNATURE = 0x43425355  // "USBC"
        private const val CSW_SIGNATURE = 0x53425355  // "USBS"
        private const val CBW_SIZE = 31
        private const val CSW_SIZE = 13
        private const val TIMEOUT_MS = 5000
        // Max sectors per batch (64KB / 512 = 128 sectors, or 64KB / 4096 = 16 sectors)
        private const val MAX_XFER_BYTES = 65536

        /**
         * Open a USB mass storage device. Returns null if not a compatible MSC device or
         * if permission is not held.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbMassStorage? {
            // Find mass storage interface (class 0x08, subclass 0x06, protocol 0x50 = BOT)
            val iface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 &&
                    it.interfaceProtocol == 0x50
                } ?: return null

            val bulkIn = (0 until iface.endpointCount)
                .map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
                } ?: return null

            val bulkOut = (0 until iface.endpointCount)
                .map { iface.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_OUT
                } ?: return null

            val conn = manager.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return null
            }

            // Temporary instance just to issue READ CAPACITY
            val probe = UsbMassStorage(conn, bulkIn, bulkOut, blockCount = 0)
            val cap = probe.readCapacity() ?: run {
                conn.close()
                return null
            }

            return UsbMassStorage(
                connection = conn,
                bulkIn     = bulkIn,
                bulkOut    = bulkOut,
                blockCount = cap.first + 1L,   // lastLba + 1 = total sectors
                blockSize  = cap.second
            )
        }
    }

    /**
     * Read [count] sectors starting at logical block address [lba].
     * Returns a ByteArray of size count*blockSize, or null on error.
     * Called from JNI.
     */
    fun readSectors(lba: Long, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val maxSectorsPerBatch = MAX_XFER_BYTES / blockSize
        val buf = ByteArray(count * blockSize)
        var offset = 0
        var remaining = count
        var currentLba = lba
        while (remaining > 0) {
            val batch = minOf(remaining, maxSectorsPerBatch)
            val batchBuf = scsiRead10(currentLba, batch) ?: return null
            System.arraycopy(batchBuf, 0, buf, offset, batchBuf.size)
            offset += batchBuf.size
            currentLba += batch
            remaining -= batch
        }
        return buf
    }

    /**
     * Write [data] starting at logical block address [lba].
     * [data].size must be a multiple of [blockSize].
     * Returns true on success. Called from JNI.
     */
    fun writeSectors(lba: Long, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val maxSectorsPerBatch = MAX_XFER_BYTES / blockSize
        var offset = 0
        var currentLba = lba
        val totalSectors = data.size / blockSize
        var remaining = totalSectors
        while (remaining > 0) {
            val batch = minOf(remaining, maxSectorsPerBatch)
            val chunk = data.copyOfRange(offset, offset + batch * blockSize)
            if (!scsiWrite10(currentLba, batch, chunk)) return false
            offset += chunk.size
            currentLba += batch
            remaining -= batch
        }
        return true
    }

    /** Issue SCSI READ CAPACITY(10) command. Returns Pair(lastLba, blockSize) or null. */
    private fun readCapacity(): Pair<Long, Int>? {
        val cdb = ByteArray(10).also { it[0] = 0x25.toByte() }  // READ CAPACITY(10)
        val buf = ByteArray(8)
        sendCommand(cdb, buf, dataIn = true) ?: return null
        val lastLba   = buf.getUInt32BE(0)
        val blkSize   = buf.getInt32BE(4)
        if (blkSize <= 0) return null
        return Pair(lastLba, blkSize)
    }

    private fun scsiRead10(lba: Long, count: Int): ByteArray? {
        require(lba in 0..0xFFFFFFFFL) { "LBA $lba exceeds READ(10) 32-bit limit" }
        val buf = ByteArray(count * blockSize)
        val cdb = ByteArray(10).apply {
            this[0] = 0x28.toByte()           // READ(10)
            putUInt32BE(2, lba)
            putInt16BE(7, count)
        }
        return if (sendCommand(cdb, buf, dataIn = true) != null) buf else null
    }

    private fun scsiWrite10(lba: Long, count: Int, data: ByteArray): Boolean {
        require(lba in 0..0xFFFFFFFFL) { "LBA $lba exceeds WRITE(10) 32-bit limit" }
        val cdb = ByteArray(10).apply {
            this[0] = 0x2A.toByte()           // WRITE(10)
            putUInt32BE(2, lba)
            putInt16BE(7, count)
        }
        return sendCommand(cdb, data, dataIn = false) != null
    }

    /**
     * Core BOT transaction: send CBW, perform data phase, receive CSW.
     * Returns the data buffer on success or null on error.
     */
    private fun sendCommand(cdb: ByteArray, data: ByteArray, dataIn: Boolean): ByteArray? {
        val tag = cbwTag++

        // Build 31-byte CBW
        val cbw = ByteArray(CBW_SIZE)
        cbw.putInt32LE(0, CBW_SIGNATURE)
        cbw.putInt32LE(4, tag)
        cbw.putInt32LE(8, data.size)
        cbw[12] = if (dataIn) 0x80.toByte() else 0x00
        cbw[13] = lun.toByte()
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)

        // Phase 1: Send CBW
        val cbwSent = connection.bulkTransfer(bulkOut, cbw, CBW_SIZE, TIMEOUT_MS)
        if (cbwSent != CBW_SIZE) return null

        // Phase 2: Data transfer
        if (data.isNotEmpty()) {
            val ep = if (dataIn) bulkIn else bulkOut
            var transferred = 0
            while (transferred < data.size) {
                val chunkSize = minOf(data.size - transferred, ep.maxPacketSize * 64)
                val n = connection.bulkTransfer(ep, data, transferred, chunkSize, TIMEOUT_MS)
                if (n < 0) return null
                transferred += n
            }
        }

        // Phase 3: Receive CSW
        val csw = ByteArray(CSW_SIZE)
        val cswLen = connection.bulkTransfer(bulkIn, csw, CSW_SIZE, TIMEOUT_MS)
        if (cswLen != CSW_SIZE) return null
        if (csw.getInt32LE(0) != CSW_SIGNATURE) return null
        if (csw.getInt32LE(4) != tag) return null
        if (csw[12] != 0.toByte()) return null  // CSW status must be 0 (Command Passed)

        return data
    }

    override fun close() {
        try { connection.close() } catch (_: Throwable) {}
    }

    // ── ByteArray integer helpers ──────────────────────────────────────────

    /** Read an unsigned 32-bit big-endian integer as a Long. */
    private fun ByteArray.getUInt32BE(off: Int): Long =
        ((this[off].toLong() and 0xFF) shl 24) or
        ((this[off+1].toLong() and 0xFF) shl 16) or
        ((this[off+2].toLong() and 0xFF) shl 8) or
         (this[off+3].toLong() and 0xFF)

    private fun ByteArray.getInt32BE(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 24) or
        ((this[off+1].toInt() and 0xFF) shl 16) or
        ((this[off+2].toInt() and 0xFF) shl 8) or
         (this[off+3].toInt() and 0xFF)

    private fun ByteArray.getInt32LE(off: Int): Int =
         (this[off].toInt() and 0xFF) or
        ((this[off+1].toInt() and 0xFF) shl 8) or
        ((this[off+2].toInt() and 0xFF) shl 16) or
        ((this[off+3].toInt() and 0xFF) shl 24)

    private fun ByteArray.putInt32BE(off: Int, v: Int) {
        this[off]   = (v ushr 24).toByte()
        this[off+1] = (v ushr 16).toByte()
        this[off+2] = (v ushr 8).toByte()
        this[off+3] = v.toByte()
    }

    private fun ByteArray.putUInt32BE(off: Int, v: Long) {
        this[off]   = ((v ushr 24) and 0xFF).toByte()
        this[off+1] = ((v ushr 16) and 0xFF).toByte()
        this[off+2] = ((v ushr 8)  and 0xFF).toByte()
        this[off+3] = (v           and 0xFF).toByte()
    }

    private fun ByteArray.putInt32LE(off: Int, v: Int) {
        this[off]   = v.toByte()
        this[off+1] = (v ushr 8).toByte()
        this[off+2] = (v ushr 16).toByte()
        this[off+3] = (v ushr 24).toByte()
    }

    private fun ByteArray.putInt16BE(off: Int, v: Int) {
        this[off]   = (v ushr 8).toByte()
        this[off+1] = v.toByte()
    }
}
