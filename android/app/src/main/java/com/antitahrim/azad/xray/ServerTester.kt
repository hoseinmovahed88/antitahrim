package com.antitahrim.azad.xray

import com.antitahrim.azad.core.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
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

    /**
     * @param ip آی‌پی‌ای که میزبان به آن ترجمه شد و اتصال با آن برقرار شد
     */
    data class Result(val link: ConfigLink, val latencyMs: Long, val ip: String)

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

    /**
     * یک اتصال TCP ساده. اگر برقرار شد، زمانش و آی‌پی مقصد را برمی‌گرداند.
     *
     * آی‌پی را نگه می‌داریم چون بعداً همان را به هسته می‌دهیم، نه نام میزبان
     * را. دو سود دارد: هسته مجبور نیست خودش نام را ترجمه کند، و مهم‌تر از
     * آن، به سراغ همان آدرسی می‌رود که اینجا ثابت شد از این شبکه قابل
     * دسترسی است.
     */
    private fun probe(link: ConfigLink): Result? {
        val started = System.nanoTime()
        return try {
            val address = InetSocketAddress(link.host, link.port)
            if (address.isUnresolved) return null
            val resolved: InetAddress = address.address ?: return null
            val ip = resolved.hostAddress ?: return null

            // ترجمه‌ای که به رنج محلی برسد یعنی پاسخ DNS دستکاری شده. در
            // ایران فیلترینگ به جای آدرس واقعی آدرس‌هایی مثل 10.10.34.36
            // برمی‌گرداند، و آن آدرس پورت را هم باز می‌گذارد. بدون این
            // بررسی، چنین میزبانی «زنده و سریع» شمرده می‌شود چون جواب از
            // چند کیلومتری می‌آید، بعد همان انتخاب می‌شود و هیچ ترافیکی از
            // آن عبور نمی‌کند.
            if (isLocalOrReserved(resolved)) {
                Report.log("پاسخ DNS دستکاری‌شده برای " + link.host + ": " + ip)
                return null
            }

            Socket().use { socket ->
                socket.connect(address, CONNECT_TIMEOUT_MS)
            }
            Result(link, (System.nanoTime() - started) / 1_000_000, ip)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // پورت یا نام میزبان غیرقابل قبول در همان فهرست عمومی
            null
        }
    }

    private fun isLocalOrReserved(address: InetAddress): Boolean =
        address.isSiteLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isAnyLocalAddress ||
            address.isMulticastAddress
}
