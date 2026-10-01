package com.antitahrim.azad

import com.antitahrim.azad.net.Http
import com.antitahrim.azad.net.IranList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IranListTest {

    /** قالب واقعی فایل ipverse: چند سطر توضیح و بعد یک رنج در هر سطر. */
    @Test
    fun `cidr list keeps ranges and drops comments and junk`() {
        val body = """
            # Country: Iran (IR)
            # Address family: IPv4
            #
            2.144.0.0/14
            5.22.0.0/17   # توضیح انتهای سطر
            not-a-range
            300.1.1.1/8
            5.22.0.0/17
        """.trimIndent()

        assertEquals(listOf("2.144.0.0/14", "5.22.0.0/17"), IranList.parseCidrs(body))
    }

    /** قالب Surge: هر نام با یک نقطه شروع می‌شود. */
    @Test
    fun `domain list is read from surge format`() {
        val body = """
            # Surge
            # Manual: https://manual.nssurge.com/rule/domain-based.html
            .digikala.com
            .Torob.COM
            .example.ir
            *.wild.net
            .bad domain.com
            .nodot
        """.trimIndent()

        val domains = IranList.parseDomains(body)
        assertEquals(listOf("digikala.com", "torob.com", "wild.net"), domains)
    }

    /** دامنه‌های ir با یک قاعده جدا پوشش داده می‌شوند و نباید فهرست را سنگین کنند. */
    @Test
    fun `ir domains are left to the tld rule`() {
        val domains = IranList.parseDomains(".shaparak.ir\n.bank.example.ir\n.aparat.com")
        assertFalse(domains.any { it.endsWith(".ir") })
        assertTrue(domains.contains("aparat.com"))
    }

    @Test
    fun `absolute redirect splits into host and path`() {
        assertEquals(
            "github.com" to "/o/r/releases/download/1/f.txt",
            Http.splitUrl("https://github.com/o/r/releases/download/1/f.txt", "x")
        )
    }

    @Test
    fun `relative redirect stays on the same host`() {
        assertEquals("github.com" to "/a/b", Http.splitUrl("/a/b", "github.com"))
    }

    /** پایین آمدن به http یعنی کنار گذاشتن TLS. دنبالش نمی‌رویم. */
    @Test
    fun `redirect to plain http is refused`() {
        assertNull(Http.splitUrl("http://example.com/x", "github.com"))
    }
}
