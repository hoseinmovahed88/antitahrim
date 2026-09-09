package com.antitahrim.azad.net

import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.random.Random

/**
 * تنظیمات تکه‌تکه کردن دست‌دادن TLS.
 *
 * سامانه‌های بازرسی بسته (DPI) اسم دامنه را از فیلد SNI داخل اولین بسته
 * دست‌دادن TLS می‌خوانند. اگر آن بسته را به چند قطعه TCP جدا بشکنیم و بین‌شان
 * کمی مکث بگذاریم، اسم دامنه در هیچ بسته‌ای کامل دیده نمی‌شود و تطبیق ساده
 * روی SNI شکست می‌خورد.
 *
 * این کار «SNI جعلی» نیست؛ SNI را کلاً از دید DPI پنهان می‌کند و بر خلاف
 * SNI جعلی، سمت سرور هم چیزی خراب نمی‌شود.
 */
data class FragmentConfig(
    val enabled: Boolean = true,
    val minChunk: Int = 8,
    val maxChunk: Int = 48,
    val minDelayMs: Long = 4,
    val maxDelayMs: Long = 14
)

/**
 * سوکتی که اولین رکورد دست‌دادن TLS را تکه‌تکه می‌فرستد.
 * بقیه ترافیک دست‌نخورده و با سرعت عادی رد می‌شود.
 */
class FragmentingSocket(private val cfg: FragmentConfig) : Socket() {

    private var wrapped: FragmentingOutputStream? = null

    override fun getOutputStream(): OutputStream {
        wrapped?.let { return it }
        val stream = FragmentingOutputStream(super.getOutputStream(), cfg)
        wrapped = stream
        return stream
    }
}

private class FragmentingOutputStream(
    private val delegate: OutputStream,
    private val cfg: FragmentConfig
) : OutputStream() {

    private var firstRecordHandled = false

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!firstRecordHandled && cfg.enabled && isTlsHandshake(b, off, len)) {
            firstRecordHandled = true
            writeFragmented(b, off, len)
            return
        }
        // هر نوشتن دیگری یعنی دست‌دادن شروع شده؛ از این به بعد کاری نمی‌کنیم
        if (len > 0) firstRecordHandled = true
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    /** رکورد TLS با 0x16 (handshake) و بعد نسخه 0x03 شروع می‌شود. */
    private fun isTlsHandshake(b: ByteArray, off: Int, len: Int): Boolean =
        len >= 3 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte()

    private fun writeFragmented(b: ByteArray, off: Int, len: Int) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val size = Random.nextInt(cfg.minChunk, cfg.maxChunk + 1).coerceAtMost(end - pos)
            delegate.write(b, pos, size)
            delegate.flush()
            pos += size
            if (pos < end) {
                val delay = Random.nextLong(cfg.minDelayMs, cfg.maxDelayMs + 1)
                runCatching { Thread.sleep(delay) }
            }
        }
    }
}

/** سوکت خام می‌سازد؛ با تکه‌تکه کردن یا بدون آن. */
internal fun openSocket(
    address: InetAddress,
    port: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    fragment: FragmentConfig
): Socket {
    val socket = if (fragment.enabled) FragmentingSocket(fragment) else Socket()
    // بدون این، لینوکس قطعه‌ها را دوباره به هم می‌چسباند و تکه‌تکه کردن بی‌اثر می‌شود
    socket.tcpNoDelay = true
    socket.soTimeout = readTimeoutMs
    socket.connect(java.net.InetSocketAddress(address, port), connectTimeoutMs)
    return socket
}
