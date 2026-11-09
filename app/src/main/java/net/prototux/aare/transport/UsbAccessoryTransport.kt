package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wired head units switch the phone to USB accessory mode (AOA) with the strings
 * manufacturer "Android", model "Android Auto", then talk AAP over the bulk endpoints.
 */
class UsbAccessoryTransport(
    usb: UsbManager,
    val accessory: UsbAccessory,
    /** Diagnostics: called once with the first bytes the head unit sends (hex), from the reader thread. */
    private val onFirstBytes: ((String) -> Unit)? = null,
) : Transport {
    private val fd: ParcelFileDescriptor = usb.openAccessory(accessory)
        ?: throw AapException("cannot open USB accessory ${accessory.model} (permission?)")

    override val name = "usb ${accessory.manufacturer} ${accessory.model} ${accessory.version}"

    // Reads from an accessory must use buffers of at least one USB bulk transfer (16 kB),
    // otherwise the kernel drops the rest of the transfer. FrameReader buffers 64 kB.
    override val input: InputStream = FirstBytesLogger(FileInputStream(fd.fileDescriptor), onFirstBytes)
    // The accessory fd has no kernel send buffer (unlike a TCP socket), so an unbuffered write
    // blocks per fragment on the USB bulk endpoint. Buffer so FrameWriter's per-fragment writes
    // coalesce into one write per message at its flush() — fewer blocking syscalls on the pipe.
    override val output: OutputStream = BufferedOutputStream(FileOutputStream(fd.fileDescriptor), 64 * 1024)

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the fd makes a blocked read() return with an error.
        runCatching { fd.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        fun isAndroidAuto(a: UsbAccessory) =
            a.manufacturer.equals("Android", true) && (a.model ?: "").startsWith("Android Auto", true)
    }

    /**
     * Passes reads through unchanged and reports the first bytes received once. Tells apart a
     * head unit that never sends anything from one whose first message AARE fails to recognise.
     */
    private class FirstBytesLogger(base: InputStream, private val report: ((String) -> Unit)?) :
        java.io.FilterInputStream(base) {
        @Volatile private var reported = report == null

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (!reported && n > 0) {
                reported = true
                val shown = minOf(n, 32)
                val hex = (0 until shown).joinToString(" ") { "%02x".format(b[off + it]) }
                runCatching { report?.invoke("$n bytes: $hex${if (n > shown) " …" else ""}") }
            }
            return n
        }

        override fun read(): Int {
            val c = super.read()
            if (!reported && c >= 0) {
                reported = true
                runCatching { report?.invoke("1 byte: %02x".format(c)) }
            }
            return c
        }
    }
}
