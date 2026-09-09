package com.antitahrim.azad.xray

import com.antitahrim.azad.core.Report
import com.antitahrim.azad.net.FragmentConfig
import com.antitahrim.azad.net.Http
import com.antitahrim.azad.net.IranDns

/**
 * فهرست‌های عمومی سرورهای رایگان.
 *
 * این فهرست‌ها را افراد و گروه‌های مختلف روی مخازن عمومی منتشر می‌کنند و
 * مرتب به‌روز می‌شوند. برنامه چند منبع را با هم می‌گیرد تا اگر یکی از کار
 * افتاد بقیه جبران کنند.
 *
 * هشدار امنیتی که در راهنما هم آمده: این سرورها را افراد ناشناس اداره
 * می‌کنند. ترافیک شما از دست آنها رد می‌شود. برای عبور از فیلترینگ خوب
 * است، برای کار حساس نه.
 */
object ConfigSources {

    private data class Source(val host: String, val path: String)

    /**
     * میزبان و مسیر جدا نگه داشته می‌شوند چون لایه HTTP ما خودش نام را با
     * DNS ایرانی حل می‌کند و دست‌دادن TLS را تکه‌تکه می‌فرستد.
     */
    private val SOURCES = listOf(
        // هر چهار مسیر در تاریخ نوشتن این کد تست شده‌اند و پاسخ ۲۰۰ می‌دهند.
        // دو منبع دیگر که ابتدا اضافه شده بودند ۴۰۴ برمی‌گرداندند و حذف شدند.
        Source("raw.githubusercontent.com", "/Epodonios/v2ray-configs/main/All_Configs_Sub.txt"),
        Source("raw.githubusercontent.com", "/mahdibland/V2RayAggregator/master/sub/sub_merge.txt"),
        // این یکی کل متن را با base64 می‌دهد؛ expand بازش می‌کند
        Source("raw.githubusercontent.com", "/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt"),
        Source("raw.githubusercontent.com", "/mahdibland/ShadowsocksAggregator/master/Eternity.txt")
    )

    /** حداکثر چند سرور از هر منبع برداشته شود، تا حافظه بی‌جهت پر نشود. */
    private const val PER_SOURCE_LIMIT = 400

    /**
     * همه منابع را می‌گیرد و سرورهای یکتا را برمی‌گرداند.
     *
     * شکست یک منبع کل کار را متوقف نمی‌کند؛ فقط ثبت می‌شود و سراغ بعدی
     * می‌رویم. اگر هیچ‌کدام جواب ندهند فهرست خالی برمی‌گردد.
     */
    fun fetchAll(fragmentTls: Boolean, strictIranDns: Boolean): List<ConfigLink> {
        val found = LinkedHashMap<String, ConfigLink>()

        for (source in SOURCES) {
            // نکته کاتلین: continue داخل لامبدای inline هنوز آزمایشی است و
            // کامپایل نمی‌شود، پس خطا به صورت null برمی‌گردد و بیرون بررسی می‌شود.
            val body = runCatching {
                Http.request(
                    method = "GET",
                    host = source.host,
                    path = source.path,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "text/plain, */*"
                    ),
                    fragment = FragmentConfig(enabled = fragmentTls),
                    resolved = IranDns
                        .resolve(source.host, allowSystemFallback = !strictIranDns)
                        .takeIf { it.isNotEmpty() }
                )
            }.getOrElse { error ->
                Report.logError("دریافت " + source.path.takeLast(28), error)
                null
            }

            if (body == null) continue

            if (body.code !in 200..299) {
                Report.log("منبع " + source.path.takeLast(28) + " کد " + body.code + " داد")
                continue
            }

            val added = parseInto(found, body.body)
            Report.log("منبع " + source.path.takeLast(28) + ": " + added + " سرور")
        }

        Report.log("مجموع سرورهای یکتا: " + found.size)
        return found.values.toList()
    }

    /**
     * متن فهرست را می‌خواند. بعضی منابع خطوط را مستقیم می‌دهند و بعضی کل
     * متن را با base64 کد می‌کنند، پس هر دو حالت امتحان می‌شود.
     */
    internal fun parseInto(into: MutableMap<String, ConfigLink>, body: String): Int {
        var added = 0
        for (line in expand(body).lineSequence()) {
            if (added >= PER_SOURCE_LIMIT) break
            val link = ConfigLink.parse(line) ?: continue
            if (into.put(link.key, link) == null) added++
        }
        return added
    }

    /** اگر کل متن با base64 کد شده باشد بازش می‌کند، وگرنه دست‌نخورده برمی‌گرداند. */
    internal fun expand(body: String): String {
        val trimmed = body.trim()
        if (trimmed.contains("://")) return trimmed

        return runCatching {
            val decoded = String(ConfigLink.decodeBase64(trimmed), Charsets.UTF_8)
            if (decoded.contains("://")) decoded else trimmed
        }.getOrDefault(trimmed)
    }
}
