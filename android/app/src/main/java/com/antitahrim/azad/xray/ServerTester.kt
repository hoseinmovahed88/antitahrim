package com.antitahrim.azad.xray

import com.antitahrim.azad.core.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * سرورهای فهرست عمومی را غربال می‌کند.
 *
 * فهرست‌ها هزاران سرور دارند و بیشترشان مرده‌اند. بالا آوردن هسته Xray برای
 * تک‌تکشان دقایق طول می‌کشد، پس اول یک غربال ارزان انجام می‌شود: آیا اصلاً
 * پورت TCP سرور از این شبکه باز است؟
 *
 * این تست اثبات نمی‌کند سرور سالم است، ولی سرورهای مرده و آنهایی که اپراتور
 * بسته را حذف می‌کند و فهرست را از هزاران به ده‌ها می‌رساند. اثبات نهایی با
 * بالا آوردن تونل و عبور واقعی ترافیک انجام می‌شود.
 */
object ServerTester {

    /** چند سرور هم‌زمان تست شوند. بیشتر از این، شبکه گوشی خفه می‌شود. */
    private const val PARALLEL = 24

    private const val CONNECT_TIMEOUT_MS = 2500

    data class Result(val link: ConfigLink, val latencyMs: Long)

    /**
     * سرورها را موازی تست می‌کند و آنهایی که پاسخ دادند را از سریع‌ترین به
     * کندترین برمی‌گرداند.
     *
     * @param limit وقتی این تعداد سرور سالم پیدا شد، بقیه رها می‌شوند
     */
    suspend fun rank(
        candidates: List<ConfigLink>,
        limit: Int = 40,
        onProgress: (tested: Int, total: Int, alive: Int) -> Unit = { _, _, _ -> }
    ): List<Result> = withContext(Dispatchers.IO) {
        val alive = mutableListOf<Result>()
        var tested = 0

        for (batch in candidates.chunked(PARALLEL)) {
            if (alive.size >= limit) break

            val results = coroutineScope {
                batch.map { link ->
                    async { probe(link) }
                }.awaitAll()
            }

            tested += batch.size
            results.filterNotNull().forEach { alive.add(it) }
            onProgress(tested, candidates.size, alive.size)
        }

        val sorted = alive.sortedBy { it.latencyMs }
        Report.log("غربال: " + sorted.size + " سرور از " + tested + " تای تست‌شده پاسخ دادند")
        sorted
    }

    /** یک اتصال TCP ساده. اگر برقرار شد، زمانش را برمی‌گرداند. */
    private fun probe(link: ConfigLink): Result? {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(link.host, link.port), CONNECT_TIMEOUT_MS)
            }
            Result(link, (System.nanoTime() - started) / 1_000_000)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // پورت یا نام میزبان غیرقابل قبول در همان فهرست عمومی
            null
        }
    }
}
