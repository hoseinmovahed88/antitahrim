package com.antitahrim.azad

import com.antitahrim.azad.xray.ConfigLink
import com.antitahrim.azad.xray.XrayConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigTest {

    private fun build(raw: String): JSONObject {
        val link = requireNotNull(ConfigLink.parse(raw)) { "لینک تجزیه نشد: $raw" }
        return JSONObject(XrayConfig.build(link, 10808))
    }

    /**
     * این تست از یک شکست واقعی روی گوشی آمده.
     *
     * کانفیگ از میان‌بر geoip:private استفاده می‌کرد که به فایل داده
     * geoip.dat نیاز دارد. آن فایل همراه برنامه نبود و Xray دنبالش در
     * /system/bin می‌گشت، پس هسته اصلاً بالا نمی‌آمد. از روی کد هیچ نشانه‌ای
     * نداشت و فقط روی دستگاه معلوم شد.
     */
    @Test
    fun `the config never leans on a data file we do not ship`() {
        val config = build("vless://id@example.com:443?security=tls&sni=a.com#s")
        val text = config.toString()

        assertFalse("geoip: به فایل داده نیاز دارد", text.contains("geoip:"))
        assertFalse("geosite: به فایل داده نیاز دارد", text.contains("geosite:"))
    }

    @Test
    fun `local destinations are routed around the tunnel`() {
        val config = build("vless://id@example.com:443#s")
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertTrue(rules.length() >= 1)

        val ips = rules.getJSONObject(0).getJSONArray("ip")
        val listed = (0 until ips.length()).map { ips.getString(it) }

        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"))
        for (expected in listOf("10.0.0.0/8", "127.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12")) {
            assertTrue("$expected باید مستقیم برود", listed.contains(expected))
        }
    }

    /**
     * اگر آی‌پی سنجیده‌شده داده شود، هسته باید به همان وصل شود، ولی نام
     * میزبان باید دست‌نخورده در SNI بماند. اگر نام به SNI نرسد، دست‌دادن
     * TLS سمت سرور رد می‌شود و از بیرون شبیه «سرور خراب» دیده می‌شود.
     */
    @Test
    fun `a verified address is used for dialing while the name stays in the sni`() {
        val link = requireNotNull(
            ConfigLink.parse("vless://id@example.com:443?security=tls&sni=example.com#s")
        )
        val config = JSONObject(XrayConfig.build(link, 10808, "203.0.113.9"))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        val server = outbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
        assertEquals("203.0.113.9", server.getString("address"))
        assertEquals(443, server.getInt("port"))

        val tls = outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings")
        assertEquals("example.com", tls.getString("serverName"))
    }

    /** بدون آی‌پی، همان نام میزبان لینک به کار می‌رود. */
    @Test
    fun `without a verified address the host name is dialed`() {
        val config = build("vless://id@example.com:443#s")
        val server = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
        assertEquals("example.com", server.getString("address"))
    }

    @Test
    fun `the socks inbound listens on the port the bridge will use`() {
        val config = build("vless://id@example.com:443#s")
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(10808, inbound.getInt("port"))
        assertEquals("socks", inbound.getString("protocol"))
    }
}
