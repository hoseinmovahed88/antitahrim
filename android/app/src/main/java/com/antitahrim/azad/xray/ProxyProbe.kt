package com.antitahrim.azad.xray

import com.antitahrim.azad.core.Report
import com.antitahrim.azad.net.IranDns
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * می‌سنجد که زنجیره Xray تا سرور واقعاً ترافیک عبور می‌دهد یا نه.
 *
 * چرا از این راه و نه با یک سوکت معمولی: برنامه عمداً از VPN کنار گذاشته
 * شده تا سوکت‌های خروجی خودش دوباره وارد تونل نشوند. نتیجه‌اش این است که
 * هر درخواست معمولی از خود برنامه از کنار تونل رد می‌شود و چیزی را ثابت
 * نمی‌کند. به جایش مستقیم به پروکسی SOCKS روی لوپ‌بک وصل می‌شویم، که از
 * کنار گذاشتن برنامه اثر نمی‌گیرد.
 *
 * دو چیز جدا سنجیده می‌شود، چون هر کدام جدا می‌تواند خراب باشد و از دید
 * کاربر هر دو یک شکل دارند: «وصل است ولی چیزی باز نمی‌شود».
 *   ۱. یک درخواست HTTP که پاسخ دقیقش را می‌دانیم.
 *   ۲. یک پرس‌وجوی DNS، چون هر برنامه‌ای پیش از هر کاری نام حل می‌کند.
 */
object ProxyProbe {

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    /**
     * این نشانی همیشه و فقط با کد ۲۰۴ و بدنه خالی جواب می‌دهد.
     *
     * فقط کد ۲۰۴ قبول است. سنجه قبلی هر پاسخی را که با «HTTP/1.» شروع
     * می‌شد موفقیت می‌شمرد، و یک سرور با کانفیگ غلط از وب‌سرور جایگزینش
     * «400 Bad Request» گرفت و «وصل» اعلام شد بی‌آنکه حتی یک بایت داده رد
     * شود.
     */
    private const val TEST_HOST = "connectivitycheck.gstatic.com"
    private const val TEST_PATH = "/generate_204"

    /** نامی که حل کردنش سنجیده می‌شود، و حل‌کننده‌ای که پرس‌وجو به آن فرستاده می‌شود. */
    private const val DNS_TEST_NAME = "www.google.com"
    private val DNS_TEST_SERVER = byteArrayOf(1, 1, 1, 1)

    fun trafficFlows(socksPort: Int): Boolean = httpWorks(socksPort) && dnsWorks(socksPort)

    private fun httpWorks(socksPort: Int): Boolean = runCatching {
        Socket().use { socket ->
            val (input, out) = open(socket, socksPort) ?: return@runCatching false
            if (!connect(input, out, domain = TEST_HOST, port = 80)) return@runCatching false

            out.write(
                ("GET $TEST_PATH HTTP/1.1\r\nHost: $TEST_HOST\r\n" +
                    "Connection: close\r\nUser-Agent: Android\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            out.flush()

            val status = read(input, 12) ?: return@runCatching false
            val line = String(status, Charsets.US_ASCII)
            val ok = line.startsWith("HTTP/1.") && line.substring(9, 12) == "204"
            if (!ok) Report.log("پاسخ نادرست از مسیر سرور: " + line.trim() + " — سرور واقعی پشتش نیست")
            ok
        }
    }.getOrElse { error ->
        Report.log("سنجش پروکسی ناموفق — " + error.javaClass.simpleName + ": " + (error.message ?: "بدون پیام"))
        false
    }

    /**
     * یک پرس‌وجوی DNS روی TCP از مسیر پروکسی. هسته هر چیزی را که به
     * پورت ۵۳ برود به بخش DNS خودش می‌دهد، پس این همان مسیری را می‌سنجد
     * که DNS همه برنامه‌های گوشی از آن رد می‌شود.
     */
    private fun dnsWorks(socksPort: Int): Boolean = runCatching {
        Socket().use { socket ->
            val (input, out) = open(socket, socksPort) ?: return@runCatching false
            if (!connect(input, out, ipv4 = DNS_TEST_SERVER, port = 53)) return@runCatching false

            val query = IranDns.buildQuery(DNS_TEST_NAME)
            out.write(byteArrayOf((query.size shr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()

            val lengthBytes = read(input, 2) ?: return@runCatching false
            val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
            val answer = read(input, length) ?: return@runCatching false
            val ok = IranDns.parseA(answer, answer.size, query).isNotEmpty()
            if (!ok) Report.log("DNS از مسیر سرور جوابی نداد")
            ok
        }
    }.getOrElse { error ->
        Report.log("سنجش DNS ناموفق — " + error.javaClass.simpleName + ": " + (error.message ?: "بدون پیام"))
        false
    }

    /** اتصال به پروکسی و دست‌دادن SOCKS5 بدون احراز هویت. */
    private fun open(socket: Socket, socksPort: Int): Pair<InputStream, OutputStream>? {
        socket.soTimeout = READ_TIMEOUT_MS
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greeting = read(input, 2) ?: return null
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            Report.log("پروکسی SOCKS دست‌دادن را نپذیرفت")
            return null
        }
        return input to out
    }

    /** درخواست CONNECT، یا با نام دامنه یا با آی‌پی نسخه ۴. */
    private fun connect(
        input: InputStream,
        out: OutputStream,
        domain: String? = null,
        ipv4: ByteArray? = null,
        port: Int
    ): Boolean {
        val address = if (domain != null) {
            val name = domain.toByteArray(Charsets.US_ASCII)
            byteArrayOf(0x03, name.size.toByte()) + name
        } else {
            byteArrayOf(0x01) + requireNotNull(ipv4)
        }
        val request = byteArrayOf(0x05, 0x01, 0x00) + address +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        out.write(request)
        out.flush()

        val reply = read(input, 4) ?: return false
        if (reply[1] != 0x00.toByte()) {
            Report.log("پروکسی اتصال را رد کرد، کد " + (reply[1].toInt() and 0xFF))
            return false
        }
        // باقی آدرس در پاسخ، که لازمش نداریم ولی باید خوانده شود
        skipBoundAddress(input, reply[3])
        return true
    }

    /** آدرسی که پروکسی در پاسخ برمی‌گرداند، طولش به نوعش بستگی دارد. */
    private fun skipBoundAddress(input: InputStream, addressType: Byte) {
        when (addressType.toInt()) {
            0x01 -> read(input, 4 + 2)           // IPv4 و پورت
            0x04 -> read(input, 16 + 2)          // IPv6 و پورت
            0x03 -> {
                val length = input.read()
                if (length > 0) read(input, length + 2)
            }
        }
    }

    private fun read(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val got = input.read(buffer, filled, count - filled)
            if (got == -1) return null
            filled += got
        }
        return buffer
    }
}
