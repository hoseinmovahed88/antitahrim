package com.antitahrim.azad.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * حل‌کننده نام سبک که فقط از DNS داخل ایران استفاده می‌کند.
 *
 * چرا؟ سه دلیل:
 *  ۱. DNS اپراتور برای دامنه‌های خارجی آلوده است و جواب جعلی می‌دهد.
 *  ۲. پرس‌وجو به 8.8.8.8 یا 1.1.1.1 خودش نشانه استفاده از ابزار دور زدن است
 *     و در بعضی شبکه‌ها مسدود یا علامت‌گذاری می‌شود.
 *  ۳. این حل‌کننده‌ها داخل ایران هستند، پس ترافیکشان داخلی حساب می‌شود،
 *     سریع است و در ترافیک عادی گم می‌شود.
 *
 * هیچ کتابخانه بیرونی لازم ندارد؛ پرس‌وجوی DNS مستقیم روی UDP ساخته می‌شود.
 */
object IranDns {

    data class Resolver(val name: String, val ip: String)

    /**
     * حل‌کننده‌های عمومی ایرانی. ترتیب اهمیت دارد؛ از بالا به پایین امتحان می‌شوند.
     * آدرس‌های 10.202.x فقط از داخل شبکه بعضی اپراتورها جواب می‌دهند
     * و بیرون از ایران عمداً شکست می‌خورند.
     */
    val RESOLVERS = listOf(
        Resolver("شکن", "178.22.122.100"),
        Resolver("شکن ۲", "185.51.200.2"),
        Resolver("الکترو", "78.157.42.100"),
        Resolver("الکترو ۲", "78.157.42.101"),
        Resolver("بگذر", "185.55.226.26"),
        Resolver("بگذر ۲", "185.55.225.25"),
        Resolver("رادار", "10.202.10.10"),
        Resolver("رادار ۲", "10.202.10.11"),
        Resolver("۴۰۳", "10.202.10.202"),
        Resolver("شاتل", "85.15.1.14"),
        Resolver("پیشگامان", "5.202.100.100")
    )

    private const val TIMEOUT_MS = 2500

    /**
     * نام را با حل‌کننده‌های ایرانی به آدرس تبدیل می‌کند.
     *
     * @param allowSystemFallback اگر هیچ حل‌کننده ایرانی جواب نداد، از حل‌کننده
     *        خود دستگاه استفاده شود. پیش‌فرض روشن است تا برنامه در بدترین حالت
     *        هم کار کند، ولی می‌شود خاموشش کرد.
     */
    fun resolve(host: String, allowSystemFallback: Boolean = true): List<InetAddress> {
        // اگر خودش آی‌پی است، کاری لازم نیست
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            runCatching { return listOf(InetAddress.getByName(host)) }
        }

        for (r in RESOLVERS) {
            val answers = runCatching { queryA(host, r.ip) }.getOrNull()
            if (!answers.isNullOrEmpty()) return answers
        }

        if (allowSystemFallback) {
            return runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    /**
     * یک پرس‌وجوی DNS از داخل تونل می‌فرستد تا ثابت شود تونل واقعاً کار می‌کند.
     * موفق شدن این پرس‌وجو یعنی دست‌دادن انجام شده و بسته‌ها رفت و برگشت دارند.
     */
    fun probeThroughTunnel(resolverIp: String = "1.1.1.1", timeoutMs: Int = 3000): Boolean =
        runCatching { queryA("cloudflare.com", resolverIp, timeoutMs).isNotEmpty() }
            .getOrDefault(false)

    /** یک پرس‌وجوی A به یک حل‌کننده مشخص می‌فرستد و آدرس‌ها را برمی‌گرداند. */
    internal fun queryA(host: String, resolverIp: String, timeoutMs: Int = TIMEOUT_MS): List<InetAddress> {
        val query = buildQuery(host)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val server = InetAddress.getByName(resolverIp)
            sock.send(DatagramPacket(query, query.size, server, 53))

            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(resp)
            } catch (e: SocketTimeoutException) {
                return emptyList()
            }
            return parseA(buf, resp.length, query)
        }
    }

    internal fun buildQuery(host: String): ByteArray {
        val out = ArrayList<Byte>(64)
        fun put(vararg values: Int) = values.forEach { out.add(it.toByte()) }

        val id = Random.nextInt(0, 0xFFFF)
        put(id shr 8, id)
        put(0x01, 0x00)                         // recursion desired
        put(0x00, 0x01)                         // یک سوال
        repeat(6) { put(0x00) }                 // answer/authority/additional = 0

        for (label in host.trim('.').split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "برچسب نامعتبر در نام: $label" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        put(0x00)                               // پایان نام
        put(0x00, 0x01)                         // QTYPE = A
        put(0x00, 0x01)                         // QCLASS = IN
        return out.toByteArray()
    }

    internal fun parseA(buf: ByteArray, len: Int, query: ByteArray): List<InetAddress> {
        if (len < 12) return emptyList()
        // شناسه پاسخ باید با شناسه پرس‌وجو یکی باشد
        if (buf[0] != query[0] || buf[1] != query[1]) return emptyList()
        val rcode = buf[3].toInt() and 0x0F
        if (rcode != 0) return emptyList()

        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount == 0) return emptyList()

        var i = 12
        repeat(qdCount) {
            i = skipName(buf, len, i)
            i += 4                              // QTYPE + QCLASS
            if (i > len) return emptyList()
        }

        val result = ArrayList<InetAddress>(anCount)
        repeat(anCount) {
            if (i >= len) return result
            i = skipName(buf, len, i)
            if (i + 10 > len) return result
            val type = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            val rdLen = ((buf[i + 8].toInt() and 0xFF) shl 8) or (buf[i + 9].toInt() and 0xFF)
            i += 10
            if (i + rdLen > len) return result
            if (type == 1 && rdLen == 4) {
                val addr = byteArrayOf(buf[i], buf[i + 1], buf[i + 2], buf[i + 3])
                runCatching { result.add(InetAddress.getByAddress(addr)) }
            }
            i += rdLen
        }
        return result
    }

    /** از روی یک نام DNS رد می‌شود، با پشتیبانی از فشرده‌سازی اشاره‌گری. */
    private fun skipName(buf: ByteArray, len: Int, start: Int): Int {
        var i = start
        while (i < len) {
            val b = buf[i].toInt() and 0xFF
            when {
                b == 0 -> return i + 1
                b and 0xC0 == 0xC0 -> return i + 2   // اشاره‌گر، دو بایت
                else -> i += b + 1
            }
        }
        return len
    }
}
