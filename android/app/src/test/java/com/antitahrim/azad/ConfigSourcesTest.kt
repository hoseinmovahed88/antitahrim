package com.antitahrim.azad

import com.antitahrim.azad.xray.ConfigLink
import com.antitahrim.azad.xray.ConfigSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    @Test
    fun `a base64 wrapped list is expanded, a plain one is left alone`() {
        val plain = "vless://a@h.com:443#one\nvless://b@h2.com:443#two"
        assertEquals(plain, ConfigSources.expand(plain))

        val wrapped = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
        assertEquals(plain, ConfigSources.expand(wrapped))
    }

    @Test
    fun `unsupported protocols are skipped without stopping the rest`() {
        val body = listOf(
            "ss://someshadowsocks@h:443#s",
            "hysteria2://x@h:443#h",
            "vless://a@h.com:443#ok",
            "garbage",
            "trojan://p@h3.com:443#ok2"
        ).joinToString("\n")

        val into = LinkedHashMap<String, ConfigLink>()
        val added = ConfigSources.parseInto(into, body)
        assertEquals(2, added)
        assertTrue(into.values.any { it.protocol == "vless" })
        assertTrue(into.values.any { it.protocol == "trojan" })
    }
}
