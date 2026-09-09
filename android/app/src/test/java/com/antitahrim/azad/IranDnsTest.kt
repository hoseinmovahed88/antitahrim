package com.antitahrim.azad

import com.antitahrim.azad.net.IranDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * پرس‌وجوی DNS با دست ساخته می‌شود، پس ساخت و خواندن بسته باید دقیق باشد.
 * این تست‌ها بدون شبکه اجرا می‌شوند و پاسخ ساختگی می‌سازند.
 */
class IranDnsTest {

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun response(
        query: ByteArray,
        name: String,
        answers: List<Pair<Int, ByteArray>>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query[0].toInt()); out.write(query[1].toInt())   // همان شناسه
        out.write(0x81); out.write(0x80)                            // پاسخ، بدون خطا
        out.write(0x00); out.write(0x01)                            // qdcount
        out.write(0x00); out.write(answers.size)                    // ancount
        out.write(0x00); out.write(0x00)                            // nscount
        out.write(0x00); out.write(0x00)                            // arcount
        out.write(encodeName(name))
        out.write(0x00); out.write(0x01)                            // QTYPE A
        out.write(0x00); out.write(0x01)                            // QCLASS IN
        for ((type, rdata) in answers) {
            out.write(0xC0); out.write(0x0C)                        // اشاره‌گر به نام سوال
            out.write(0x00); out.write(type)
            out.write(0x00); out.write(0x01)
            out.write(0x00); out.write(0x00); out.write(0x01); out.write(0x2C)  // ttl
            out.write(0x00); out.write(rdata.size)
            out.write(rdata)
        }
        return out.toByteArray()
    }

    @Test
    fun `query packet is well formed`() {
        val q = IranDns.buildQuery("example.com")
        assertEquals(0x01, q[2].toInt() and 0xFF)          // recursion desired
        assertEquals(1, ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF))
        assertEquals(1, q[q.size - 1].toInt())             // QCLASS = IN
        assertTrue(String(q, 12, 8, Charsets.US_ASCII).contains("example"))
    }

    @Test
    fun `an A record is read back correctly`() {
        val q = IranDns.buildQuery("example.com")
        val ip = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
        val packet = response(q, "example.com", listOf(1 to ip))

        val result = IranDns.parseA(packet, packet.size, q)
        assertEquals(1, result.size)
        assertEquals("93.184.216.34", result[0].hostAddress)
    }

    @Test
    fun `a CNAME before the address is skipped`() {
        val q = IranDns.buildQuery("store.example.com")
        val cname = encodeName("cdn.example.net")
        val ip = byteArrayOf(1, 2, 3, 4)
        val packet = response(q, "store.example.com", listOf(5 to cname, 1 to ip))

        val result = IranDns.parseA(packet, packet.size, q)
        assertEquals(1, result.size)
        assertEquals("1.2.3.4", result[0].hostAddress)
    }

    @Test
    fun `a reply with the wrong transaction id is rejected`() {
        val q = IranDns.buildQuery("example.com")
        val packet = response(q, "example.com", listOf(1 to byteArrayOf(1, 2, 3, 4)))
        packet[0] = (packet[0] + 1).toByte()

        assertTrue(IranDns.parseA(packet, packet.size, q).isEmpty())
    }
}
