package com.antitahrim.azad.net

import android.content.Context
import com.antitahrim.azad.core.Report
import java.io.File

/**
 * فهرست مقصدهای ایرانی، برای اینکه ترافیک داخلی وارد تونل نشود.
 *
 * چرا این کار مهم است: ترافیک ایرانی که از تونل رد شود سه بار ضرر می‌کند.
 * کند می‌شود چون باید تا سرور خارج و برگردد، سرور رایگان را بی‌جهت شلوغ
 * می‌کند، و بدتر از همه خیلی از سایت‌های ایرانی — بانک‌ها، درگاه‌های
 * پرداخت، سایت‌های دولتی — اتصال از آی‌پی خارجی را رد می‌کنند. پس وصل بودن
 * به تونل یعنی نتوانستن کار بانکی.
 *
 * فهرست دو بخش دارد:
 *   آی‌پی‌ها، که از رجیستری منطقه‌ای می‌آید و تکلیف هر مقصدی را روشن می‌کند
 *     بی‌آنکه لازم باشد نامش را بشناسیم.
 *   نام‌ها، برای سایت‌های ایرانی که روی میزبان خارجی نشسته‌اند و از آی‌پی
 *     نمی‌شود شناختشان.
 *
 * هر دو هر چند روز یک بار به‌روز می‌شوند. اگر به‌روزرسانی نشد، فهرست
 * همراه برنامه کار را راه می‌اندازد؛ کوچک‌تر است ولی هیچ‌وقت خالی نیست.
 */
object IranList {

    data class Lists(val cidrs: List<String>, val domains: List<String>) {
        val isEmpty: Boolean get() = cidrs.isEmpty() && domains.isEmpty()
    }

    private const val CIDR_FILE = "iran-cidr.txt"
    private const val DOMAIN_FILE = "iran-domains.txt"
    private const val PREFS = "azad_iran_list"
    private const val KEY_UPDATED = "updated_at"

    /** هر هفته یک بار. رنج‌های آی‌پی کشور سریع‌تر از این عوض نمی‌شوند. */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * سقف تعداد نام‌ها. فهرست کامل حدود شصت هزار نام دارد و همان هم اندازه
     * معقولی است، ولی اگر منبع روزی چیز عجیبی برگرداند نباید کانفیگ به
     * اندازه‌ای برسد که هسته یا گوشی را از نفس بیندازد.
     */
    private const val MAX_DOMAINS = 70_000

    /** اگر دریافت از این کمتر داشت یعنی چیزی غلط است؛ فهرست قبلی نگه داشته می‌شود. */
    private const val MIN_CIDRS = 200
    private const val MIN_DOMAINS = 500

    private val CIDR_SOURCE = Source(
        "raw.githubusercontent.com",
        "/ipverse/rir-ip/master/country/ir/ipv4-aggregated.txt"
    )

    /**
     * فایل ریلیز پروژه iran-hosted-domains. فقط نام‌های غیر ir در آن است،
     * که دقیقاً همان چیزی است که لازم داریم: دامنه‌های ir با یک قاعده
     * کوتاه پوشش داده می‌شوند و نیازی به شصت هزار سطر ندارند.
     */
    private val DOMAIN_SOURCE = Source(
        "github.com",
        "/bootmortis/iran-hosted-domains/releases/latest/download/surge_domainset_other.txt"
    )

    private data class Source(val host: String, val path: String)

    @Volatile
    private var cache: Lists? = null

    /**
     * فهرست را برمی‌گرداند؛ اگر دریافتی روی دیسک باشد همان، وگرنه فهرست
     * همراه برنامه. خواندن از دیسک یک بار انجام می‌شود و در حافظه می‌ماند.
     */
    fun load(context: Context): Lists {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }

            val cidrs = readLines(file(context, CIDR_FILE))
                .takeIf { it.size >= MIN_CIDRS }
                ?: Cidr.IRAN_RANGES
            val domains = readLines(file(context, DOMAIN_FILE))
                .takeIf { it.size >= MIN_DOMAINS }
                ?: BUNDLED_DOMAINS

            val lists = Lists(cidrs, domains)
            cache = lists
            return lists
        }
    }

    fun lastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED, 0L)

    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - lastUpdated(context) > MAX_AGE_MS

    /**
     * اگر فهرست کهنه شده بود تازه‌اش می‌کند. شکست دریافت بی‌اهمیت است و
     * فقط ثبت می‌شود؛ فهرست قبلی یا فهرست همراه برنامه سر جایش می‌ماند.
     *
     * این تابع شبکه می‌زند، پس باید روی نخ پس‌زمینه صدا زده شود.
     */
    fun refreshIfStale(context: Context, fragmentTls: Boolean, strictIranDns: Boolean) {
        if (!isStale(context)) return
        Report.log("به‌روزرسانی فهرست مقصدهای ایرانی")

        val cidrs = fetch(CIDR_SOURCE, fragmentTls, strictIranDns)
            ?.let { parseCidrs(it) }
            ?.takeIf { it.size >= MIN_CIDRS }
        val domains = fetch(DOMAIN_SOURCE, fragmentTls, strictIranDns)
            ?.let { parseDomains(it) }
            ?.takeIf { it.size >= MIN_DOMAINS }

        if (cidrs != null) {
            writeLines(file(context, CIDR_FILE), cidrs)
            Report.log("رنج‌های ایران به‌روز شد: " + cidrs.size)
        }
        if (domains != null) {
            writeLines(file(context, DOMAIN_FILE), domains)
            Report.log("نام‌های ایرانی به‌روز شد: " + domains.size)
        }

        if (cidrs == null && domains == null) {
            Report.log("به‌روزرسانی فهرست نشد، فهرست قبلی استفاده می‌شود")
            return
        }

        // زمان فقط وقتی ثبت می‌شود که چیزی واقعاً گرفته شده باشد، وگرنه یک
        // شبکه قطع می‌توانست یک هفته جلوی تلاش بعدی را بگیرد.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        cache = null
    }

    private fun fetch(source: Source, fragmentTls: Boolean, strictIranDns: Boolean): String? =
        runCatching {
            val response = Http.requestFollowing(
                host = source.host,
                path = source.path,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Accept" to "text/plain, */*"
                ),
                fragment = FragmentConfig(enabled = fragmentTls),
                strictIranDns = strictIranDns
            )
            if (response.code !in 200..299) {
                Report.log("فهرست " + source.path.takeLast(24) + " کد " + response.code + " داد")
                null
            } else {
                response.body
            }
        }.getOrElse { error ->
            Report.logError("دریافت فهرست " + source.path.takeLast(24), error)
            null
        }

    /** هر سطر یک رنج است. توضیحات و سطرهای بی‌ربط دور ریخته می‌شوند. */
    internal fun parseCidrs(body: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in body.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (Cidr.parse(line) == null) continue
            out.add(line)
        }
        return out.toList()
    }

    /**
     * نام‌ها را از قالب Surge بیرون می‌کشد، که هر سطرش با یک نقطه شروع
     * می‌شود. نام‌های ir دور ریخته می‌شوند چون یک قاعده کوتاه پوشششان
     * می‌دهد و نگه داشتنشان فقط کانفیگ را بی‌جهت سنگین می‌کند.
     */
    internal fun parseDomains(body: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in body.lineSequence()) {
            if (out.size >= MAX_DOMAINS) break
            var line = raw.substringBefore('#').trim().lowercase()
            if (line.isEmpty()) continue
            line = line.trimStart('.', '*')
            if (line.isEmpty() || !line.contains('.')) continue
            if (line.endsWith(".ir")) continue
            if (line.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) continue
            out.add(line)
        }
        return out.toList()
    }

    private fun file(context: Context, name: String) = File(context.filesDir, name)

    private fun readLines(file: File): List<String> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun writeLines(file: File, lines: List<String>) {
        runCatching { file.writeText(lines.joinToString("\n")) }
            .onFailure { Report.logError("نوشتن " + file.name, it) }
    }

    /**
     * فهرست همراه برنامه. کوتاه است و فقط نام‌های ایرانی پرکاربردی را دارد
     * که روی میزبان خارجی نشسته‌اند، تا اگر هیچ‌وقت به‌روزرسانی نشد هم
     * بانک و پرداخت و خرید کار کند.
     */
    private val BUNDLED_DOMAINS = listOf(
        "digikala.com",
        "torob.com",
        "basalam.com",
        "snapp.market",
        "snapfood.ir",
        "tapsi.cab",
        "aparat.com",
        "filimo.com",
        "telewebion.com",
        "varzesh3.com",
        "zoomit.ir",
        "zarinpal.com",
        "idpay.ir",
        "nextpay.org",
        "payping.ir",
        "irancell.ir",
        "mci.ir",
        "rightel.ir",
        "shatel.ir",
        "asiatech.ir",
        "parspack.com",
        "arvancloud.ir",
        "iranserver.com",
        "cafebazaar.ir",
        "myket.ir",
        "divar.ir",
        "sheypoor.com",
        "jabama.com",
        "alibaba.ir",
        "flytoday.ir",
        "snapptrip.com",
        "namasha.com",
        "virgool.io",
        "quera.org",
        "sanjesh.org",
        "irancell.com"
    )
}
