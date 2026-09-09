package com.antitahrim.azad

import com.antitahrim.azad.xray.ConfigLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * لینک‌های عمومی را آدم‌های مختلف تولید می‌کنند و هیچ تضمینی برای درست بودنشان
 * نیست. تجزیه‌کننده باید هر خط خراب را بی‌سر و صدا رد کند و هیچ‌وقت نیندازد.
 */
class ConfigLinkTest {

    @Test
    fun `a vless link with reality parameters is read`() {
        val link = ConfigLink.parse(
            "vless://4f1a2b3c-1111-2222-3333-abcdefabcdef@203.0.113.10:443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality" +
                "&sni=www.google.com&fp=chrome&pbk=SOMEKEY&sid=a1b2&type=tcp#%D8%AA%D8%B3%D8%AA"
        )
        assertNotNull(link)
        requireNotNull(link)
        assertEquals("vless", link.protocol)
        assertEquals("203.0.113.10", link.host)
        assertEquals(443, link.port)
        assertEquals("reality", link.params["security"])
        assertEquals("www.google.com", link.params["sni"])
        assertEquals("SOMEKEY", link.params["pbk"])
        // نام سرور با درصد کدشده بود و باید رمزگشایی شود
        assertEquals("تست", link.remark)
    }

    @Test
    fun `an IPv6 host is not confused with the port separator`() {
        val link = ConfigLink.parse("vless://abc@[2606:4700:d0::a29f:c001]:8443?security=tls#v6")
        assertNotNull(link)
        requireNotNull(link)
        assertEquals("2606:4700:d0::a29f:c001", link.host)
        assertEquals(8443, link.port)
    }

    @Test
    fun `a trojan link is read`() {
        val link = ConfigLink.parse("trojan://secret@example.com:443?security=tls&sni=a.com#T")
        assertNotNull(link)
        requireNotNull(link)
        assertEquals("trojan", link.protocol)
        assertEquals("secret", link.id)
        assertEquals("a.com", link.params["sni"])
    }

    @Test
    fun `a vmess link is decoded from base64`() {
        // {"add":"1.2.3.4","port":"443","id":"uuid-here","net":"ws","tls":"tls","ps":"nm","path":"/x"}
        val json = """{"add":"1.2.3.4","port":"443","id":"uuid-here","net":"ws","tls":"tls","ps":"nm","path":"/x"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val link = ConfigLink.parse("vmess://$encoded")
        assertNotNull(link)
        requireNotNull(link)
        assertEquals("vmess", link.protocol)
        assertEquals("1.2.3.4", link.host)
        assertEquals(443, link.port)
        assertEquals("ws", link.params["net"])
        assertEquals("nm", link.remark)
    }

    @Test
    fun `broken and unknown lines are rejected rather than thrown`() {
        val bad = listOf(
            "",
            "   ",
            "hello world",
            "ss://not-supported-here",
            "vless://",
            "vless://onlyid@",
            "vless://id@host",            // پورت ندارد
            "vless://id@host:notaport",
            "vless://id@host:99999",      // پورت خارج از محدوده
            "vmess://%%%not-base64%%%"
        )
        for (line in bad) {
            assertNull("باید رد می‌شد: $line", ConfigLink.parse(line))
        }
    }

    @Test
    fun `the key is stable so duplicates collapse`() {
        val a = ConfigLink.parse("vless://id@h.com:443?security=tls#one")
        val b = ConfigLink.parse("vless://id@h.com:443?security=tls#two")
        requireNotNull(a); requireNotNull(b)
        assertEquals(a.key, b.key)
    }

    @Test
    fun `base64 decoding accepts both alphabets and missing padding`() {
        val text = "vless://id@h.com:443#x"
        val standard = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val noPadding = standard.trimEnd('=')
        val urlSafe = java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray())

        for (encoded in listOf(standard, noPadding, urlSafe)) {
            assertEquals(text, String(ConfigLink.decodeBase64(encoded)))
        }
    }
}
