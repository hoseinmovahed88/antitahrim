package com.antitahrim.azad.xray

import com.antitahrim.azad.core.Report
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * می‌سنجد که زنجیره Xray تا سرور واقعاً ترافیک عبور می‌دهد یا نه.
 *
 * چرا از این راه و نه با یک سوکت معمولی: برنامه عمداً از VPN کنار گذاشته
 * شده تا سوکت‌های خروجی خودش دوباره وارد تونل نشوند. نتیجه‌اش این است که
 * هر درخواست معمولی از خود برنامه از کنار تونل رد می‌شود و چیزی را ثابت
 * نمی‌کند. سنجه قبلی دقیقاً همین اشتباه را داشت و همیشه موفق گزارش می‌داد.
 *
 * اینجا به جایش مستقیم به پروکسی SOCKS روی لوپ‌بک وصل می‌شویم. لوپ‌بک از
 * کنار گذاشتن برنامه اثر نمی‌گیرد، و اگر پاسخ بیاید یعنی Xray واقعاً به
 * سرور رسیده و سرور جواب داده.
 */
object ProxyProbe {

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 8000

    /** میزبانی که پاسخ کوتاه و بدون محتوا می‌دهد، پس تست سبک می‌ماند. */
    private const val TEST_HOST = "connectivitycheck.gstatic.com"
    private const val TEST_PATH = "/generate_204"

    fun trafficFlows(socksPort: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // دست‌دادن SOCKS5 بدون احراز هویت
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = read(input, 2) ?: return@runCatching false
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                Report.log("پروکسی SOCKS دست‌دادن را نپذیرفت")
                return@runCatching false
            }

            // درخواست اتصال به میزبان آزمایشی، با نام نه آی‌پی
            val host = TEST_HOST.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(7 + host.size)
            request[0] = 0x05            // نسخه
            request[1] = 0x01            // CONNECT
            request[2] = 0x00            // رزرو
            request[3] = 0x03            // آدرس از نوع نام دامنه
            request[4] = host.size.toByte()
            System.arraycopy(host, 0, request, 5, host.size)
            request[5 + host.size] = 0x00    // پورت ۸۰، بایت پرارزش
            request[6 + host.size] = 0x50    // پورت ۸۰، بایت کم‌ارزش
            out.write(request)
            out.flush()

            val reply = read(input, 4) ?: return@runCatching false
            if (reply[1] != 0x00.toByte()) {
                Report.log("پروکسی اتصال را رد کرد، کد " + (reply[1].toInt() and 0xFF))
                return@runCatching false
            }
            // باقی آدرس در پاسخ، که لازمش نداریم ولی باید خوانده شود
            skipBoundAddress(input, reply[3])

            out.write(
                ("GET $TEST_PATH HTTP/1.1\r\nHost: $TEST_HOST\r\n" +
                    "Connection: close\r\nUser-Agent: Android\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            out.flush()

            val status = read(input, 12) ?: return@runCatching false
            val line = String(status, Charsets.US_ASCII)
            val ok = line.startsWith("HTTP/1.")
            if (!ok) Report.log("پاسخ از پروکسی HTTP نبود: " + line.trim())
            ok
        }
    }.getOrElse { error ->
        Report.log("سنجش پروکسی ناموفق — " + error.javaClass.simpleName + ": " + (error.message ?: "بدون پیام"))
        false
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
