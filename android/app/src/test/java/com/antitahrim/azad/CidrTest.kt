package com.antitahrim.azad

import com.antitahrim.azad.net.Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrTest {

    private fun covers(blocks: List<Cidr.Block>, ip: String): Boolean {
        val target = Cidr.parse("$ip/32")!!.addr
        return blocks.any { it.start <= target && target <= it.end }
    }

    @Test
    fun `parse normalises an address that is not on a block boundary`() {
        val block = Cidr.parse("5.112.34.7/12")!!
        assertEquals("5.112.0.0/12", block.toString())
    }

    @Test
    fun `complement of a single block covers everything else exactly once`() {
        val result = Cidr.complement(listOf("10.0.0.0/8")).map { Cidr.parse(it)!! }
        val total = result.sumOf { it.size }
        assertEquals((1L shl 32) - (1L shl 24), total)
        assertTrue(covers(result, "8.8.8.8"))
        assertFalse(covers(result, "10.1.2.3"))
    }

    @Test
    fun `complement of the Iranian ranges is exact and usable`() {
        val excluded = Cidr.IRAN_RANGES.mapNotNull { Cidr.parse(it) }
        val result = Cidr.complement(Cidr.IRAN_RANGES).map { Cidr.parse(it)!! }

        // هیچ آدرسی نباید هم داخل تونل باشد هم بیرونش
        for (ip in listOf("5.112.0.1", "178.22.122.100", "217.218.0.1", "185.51.200.2")) {
            assertFalse("$ip باید مستقیم برود", covers(result, ip))
        }
        // مقصدهای خارجی باید داخل تونل باشند
        for (ip in listOf("8.8.8.8", "1.1.1.1", "104.16.0.1", "162.159.192.1")) {
            assertTrue("$ip باید از تونل برود", covers(result, ip))
        }

        // مجموع دو طرف باید دقیقاً کل فضای آدرس شود
        val excludedSize = mergedSize(excluded)
        val complementSize = result.sumOf { it.size }
        assertEquals(1L shl 32, excludedSize + complementSize)

        // فهرست نباید آنقدر بزرگ شود که راه‌اندازی تونل را بخواباند
        assertTrue("فهرست مسیر خیلی بزرگ است: ${result.size}", result.size < 20_000)
    }

    /** اندازه اجتماع رنج‌ها، با در نظر گرفتن هم‌پوشانی‌ها. */
    private fun mergedSize(blocks: List<Cidr.Block>): Long {
        val sorted = blocks.sortedBy { it.start }
        var total = 0L
        var cursor = -1L
        for (b in sorted) {
            val from = maxOf(b.start, cursor + 1)
            if (b.end >= from) {
                total += b.end - from + 1
                cursor = b.end
            }
        }
        return total
    }
}
