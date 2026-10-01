package com.antitahrim.azad.xray

import com.antitahrim.azad.net.IranList
import org.json.JSONArray
import org.json.JSONObject

/**
 * کانفیگ Xray را از روی یک لینک عمومی می‌سازد.
 *
 * ساختار همیشه یکی است: یک ورودی socks روی 127.0.0.1 که پل tun به آن وصل
 * می‌شود، و یک خروجی که همان سرور لینک است.
 */
object XrayConfig {

    /**
     * رنج‌های محلی و رزروشده، نوشته‌شده به صورت صریح.
     *
     * وسوسه‌انگیز است که به جای این فهرست از میان‌بر `geoip:private` استفاده
     * شود، ولی آن میان‌بر به فایل داده geoip.dat نیاز دارد. Xray آن فایل را
     * در پوشه کاری فرایند می‌گردد، که در اندروید /system/bin است و نوشتنی
     * هم نیست. نتیجه‌اش این بود که هسته اصلاً بالا نمی‌آمد.
     *
     * نوشتن مستقیم رنج‌ها همان کار را می‌کند، بدون هیچ فایل همراهی و بدون
     * ده مگابایت اضافه شدن به حجم برنامه.
     */
    private val PRIVATE_RANGES = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.88.99.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "255.255.255.255/32",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    )

    private const val FRAGMENT_TAG = "fragment"
    private const val DNS_TAG = "dns-out"

    /** دامنه‌های سطح بالای ایران: ir و معادل فارسی‌اش به شکل punycode. */
    private val IRAN_TLDS = listOf("ir", "xn--mgba3a4f16a")

    /**
     * @param socksPort پورتی که پل tun2socks به آن وصل می‌شود
     * @param link سروری که ترافیک از آن بیرون می‌رود
     * @param serverIp اگر داده شود، هسته به جای ترجمه دوباره نام میزبان
     *   مستقیم به همین آدرس وصل می‌شود. نام میزبان همان‌جا که باید بماند
     *   می‌ماند، یعنی در SNI و در هدر Host، پس چیزی برای DPI عوض نمی‌شود.
     * @param iranDirect اگر داده شود، مقصدهای ایرانی از تونل رد نمی‌شوند و
     *   مستقیم از شبکه خود گوشی می‌روند.
     * @param fragment اولین بسته دست‌دادن TLS تا سرور تکه‌تکه فرستاده شود.
     */
    fun build(
        link: ConfigLink,
        socksPort: Int,
        serverIp: String? = null,
        iranDirect: IranList.Lists? = null,
        fragment: Boolean = false
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put(
                        "settings",
                        JSONObject().put("auth", "noauth").put("udp", true)
                    )
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls"))
                    )
            )
        )

        val useFragment = fragment && usesTls(link)
        val outbounds = JSONArray()
            .put(outbound(link, serverIp, useFragment))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            .put(JSONObject().put("tag", DNS_TAG).put("protocol", "dns"))
        if (useFragment) outbounds.put(fragmentOutbound())
        root.put("outbounds", outbounds)

        root.put("dns", dns())

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules(iranDirect))
        )

        return root.toString()
    }

    /** یک خانه در آزمون هم‌زمان: کدام سرور، با تکه‌تکه کردن یا بی آن، روی کدام پورت. */
    data class ScreenSlot(
        val link: ConfigLink,
        val serverIp: String?,
        val fragment: Boolean,
        val port: Int
    )

    /** آیا تکه‌تکه کردن برای این سرور معنا دارد؛ فقط وقتی TLS در کار است. */
    fun canFragment(link: ConfigLink): Boolean = usesTls(link)

    /**
     * کانفیگ آزمون هم‌زمان: یک هسته، چند سرور.
     *
     * هر خانه یک ورودی SOCKS روی پورت خودش دارد که فقط به خروجی سرور خودش
     * می‌رود. پس سنجه می‌تواند همه را با هم امتحان کند. امتحان تک‌تک هر
     * سرور مرده سیزده ثانیه طول می‌کشید و در هر دور فقط شش سرور به نوبت
     * می‌رسید؛ هم‌زمان، چند ده سرور در همان چند ثانیه سنجیده می‌شوند.
     *
     * DNS و قواعد ایران اینجا لازم نیستند؛ این فقط آزمون است. سرور برنده
     * بعداً با کانفیگ کامل یک بار دیگر و کامل‌تر سنجیده می‌شود.
     */
    fun buildScreening(slots: List<ScreenSlot>): String {
        val inbounds = JSONArray()
        // اولین خروجی پیش‌فرض Xray است؛ هر چیزی که به قاعده‌ای نخورد دور ریخته شود
        val outbounds = JSONArray().put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()

        slots.forEachIndexed { i, slot ->
            val inTag = "in-$i"
            val outTag = "out-$i"
            inbounds.put(
                JSONObject()
                    .put("tag", inTag)
                    .put("listen", "127.0.0.1")
                    .put("port", slot.port)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            )
            outbounds.put(
                outbound(slot.link, slot.serverIp, slot.fragment && usesTls(slot.link), outTag)
            )
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put(inTag))
                    .put("outboundTag", outTag)
            )
        }
        if (slots.any { it.fragment && usesTls(it.link) }) outbounds.put(fragmentOutbound())

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", rules)
            )
            .toString()
    }

    /**
     * قواعد مسیریابی. Xray از بالا به پایین می‌خواند و اولین قاعده‌ای که
     * بخواند برنده است، پس ترتیبشان مهم است.
     *
     * قاعده نام‌ها جلوتر از قاعده آی‌پی‌ها می‌آید: مقصدی که نامش شناخته شده
     * تکلیفش روشن است و سنجیدن آی‌پی‌اش کار اضافه است. نام مقصد از خود
     * ترافیک بیرون کشیده می‌شود (sniffing در ورودی روشن است)، چون پل tun
     * فقط آی‌پی می‌دهد و نامی همراهش نیست.
     */
    private fun rules(iranDirect: IranList.Lists?): JSONArray {
        val rules = JSONArray()

        // هر پرس‌وجوی DNS، از هر برنامه‌ای، به بخش DNS خود هسته می‌رود.
        // آنجا با DoH و از روی همان اتصال TCP سرور حل می‌شود. اگر این نبود،
        // DNS به شکل UDP خام به سرور فرستاده می‌شد و خیلی از سرورهای رایگان
        // اصلاً UDP رد نمی‌کنند؛ برنامه‌ها هیچ نامی را حل نمی‌کردند و تونل
        // «وصل» بود و هیچ سایتی باز نمی‌شد.
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("port", "53")
                .put("outboundTag", DNS_TAG)
        )

        // مقصدهای محلی هیچ‌وقت نباید وارد تونل شوند، وگرنه حلقه می‌سازند
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("ip", JSONArray().apply { PRIVATE_RANGES.forEach { put(it) } })
                .put("outboundTag", "direct")
        )

        if (iranDirect != null && !iranDirect.isEmpty) addIranRules(rules, iranDirect)

        // QUIC روی UDP 443 بسته می‌شود. یوتیوب و کروم اول QUIC را امتحان
        // می‌کنند؛ اگر سرور UDP رد نکند، چند ثانیه منتظر می‌مانند تا به TCP
        // برگردند. بستنش یعنی برگشت فوری به TCP، که همیشه از سرور رد می‌شود.
        // بعد از قواعد ایران می‌آید تا QUIC مقصدهای ایرانی مستقیم برود.
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("network", "udp")
                .put("port", "443")
                .put("outboundTag", "block")
        )

        return rules
    }

    private fun addIranRules(rules: JSONArray, iranDirect: IranList.Lists) {
        // دامنه‌های ir و ایران. دو سطر، جای شصت هزار سطر.
        val domains = JSONArray()
        IRAN_TLDS.forEach { domains.put("domain:" + it) }
        iranDirect.domains.forEach { domains.put("domain:" + it) }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("domain", domains)
                .put("outboundTag", "direct")
        )

        val iranIps = JSONArray()
        iranDirect.cidrs.forEach { iranIps.put(it) }
        if (iranIps.length() > 0) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", iranIps)
                    .put("outboundTag", "direct")
            )
        }
    }

    /**
     * DNS خود هسته. پرس‌وجوها با DoH و از مسیر سرور رد می‌شوند، پس نه
     * DNS آلوده اپراتور دخالت دارد و نه لازم است سرور UDP پشتیبانی کند.
     *
     * فقط IPv4 برگردانده می‌شود. خیلی از سرورهای رایگان IPv6 ندارند؛ اگر
     * برنامه‌ای آدرس IPv6 بگیرد اول آن را امتحان می‌کند، شکست می‌خورد و
     * تازه بعد از مکث سراغ IPv4 می‌رود.
     */
    private fun dns(): JSONObject = JSONObject()
        .put(
            "servers",
            JSONArray()
                .put("https://1.1.1.1/dns-query")
                .put("https://8.8.8.8/dns-query")
        )
        .put("queryStrategy", "UseIPv4")

    private fun outbound(
        link: ConfigLink,
        serverIp: String?,
        viaFragment: Boolean,
        tag: String = "proxy"
    ): JSONObject {
        val stream = streamSettings(link)
        if (viaFragment) {
            // اتصال به سرور از دل خروجی fragment رد می‌شود، که دست‌دادنش را تکه‌تکه می‌کند
            stream.put("sockopt", JSONObject().put("dialerProxy", FRAGMENT_TAG))
        }
        return JSONObject()
            .put("tag", tag)
            .put("protocol", link.protocol)
            .put("settings", settings(link, serverIp ?: link.host))
            .put("streamSettings", stream)
    }

    /**
     * تکه‌تکه کردن ClientHello تا خود سرور.
     *
     * چرا لازم شد: بیشتر سرورهای فهرست‌های عمومی پشت Cloudflare هستند. آی‌پی
     * Cloudflare از ایران باز است، پس غربال TCP رد می‌شود و تونل «بالا می‌آید».
     * ولی سامانه بازرسی نام دامنه را در اولین بسته TLS می‌خواند و نام
     * سرورهای شناخته‌شده را می‌بندد. این محتمل‌ترین توضیح گزارشی است که
     * تونل برای همه نامزدهای پشت Cloudflare بالا آمد و هیچ‌کدام ترافیک رد
     * نکرد؛ آی‌پی باز بود و چیزی بعد از اتصال TCP جلوی کار را می‌گرفت.
     *
     * همان کاری که لایه HTTP خود برنامه برای گرفتن فهرست‌ها می‌کند و از
     * همین شبکه جواب داده، اینجا هم انجام می‌شود: ClientHello به رکوردهای
     * کوچک شکسته می‌شود تا نام دامنه هیچ‌وقت یک‌جا در یک رکورد نباشد.
     *
     * maxSplit تعداد تکه‌ها را محدود می‌کند. نام دامنه در چند صد بایت اول
     * است؛ شکستن بقیه دست‌دادن فقط هر اتصال تازه را کند می‌کرد.
     */
    private fun fragmentOutbound(): JSONObject = JSONObject()
        .put("tag", FRAGMENT_TAG)
        .put("protocol", "freedom")
        .put(
            "settings",
            JSONObject().put(
                "fragment",
                JSONObject()
                    .put("packets", "tlshello")
                    .put("length", "10-30")
                    .put("interval", "4-12")
                    .put("maxSplit", "10-20")
            )
        )

    private fun usesTls(link: ConfigLink): Boolean {
        val security = effectiveSecurity(link)
        return security == "tls" || security == "reality"
    }

    /**
     * لایه امنیتی واقعی اتصال به سرور.
     *
     * Trojan همیشه روی TLS است و خیلی از لینک‌ها اصلاً security را نمی‌نویسند.
     * خواندن این نبود به معنای «بدون رمزنگاری» یک باگ واقعی ساخت: برنامه با
     * متن ساده به پورت TLS سرور حرف زد، وب‌سرور جایگزین سرور با
     * «HTTP/1.1 400 Bad Request» جواب داد، و سنجه آن را موفقیت شمرد. نتیجه
     * «وصل شد» بود بدون اینکه یک بایت داده رد شود.
     */
    private fun effectiveSecurity(link: ConfigLink): String {
        val raw = link.params["security"].orEmpty()
        return when {
            // vmess تنها مقدار "tls" را می‌گذارد و گاهی true
            raw == "true" -> "tls"
            raw.isBlank() && link.protocol == "trojan" -> "tls"
            else -> raw
        }
    }

    private fun settings(link: ConfigLink, address: String): JSONObject = when (link.protocol) {
        "vless" -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", address)
                    .put("port", link.port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", link.id)
                                .put("encryption", link.params["encryption"] ?: "none")
                                .apply {
                                    link.params["flow"]?.takeIf { it.isNotBlank() }
                                        ?.let { put("flow", it) }
                                }
                        )
                    )
            )
        )

        "vmess" -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", address)
                    .put("port", link.port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", link.id)
                                .put("alterId", link.params["alterId"]?.toIntOrNull() ?: 0)
                                .put("security", link.params["scy"] ?: "auto")
                        )
                    )
            )
        )

        "trojan" -> JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", address)
                    .put("port", link.port)
                    .put("password", link.id)
            )
        )

        else -> JSONObject()
    }

    private fun streamSettings(link: ConfigLink): JSONObject {
        // در لینک‌های vless و trojan نوع انتقال در type است. در vmess نوع
        // انتقال در net است و type نوع سرآیند است، با مقادیری مثل none و
        // http و auto. خواندن type برای vmess، صد و سی سرور از فهرست‌ها را
        // با «unknown transport protocol: auto» از کار می‌انداخت، و چون در
        // آزمون هم‌زمان همه در یک کانفیگ‌اند، هر کدام کل آزمون را می‌کشت.
        val network = if (link.protocol == "vmess") {
            link.params["net"]?.ifBlank { null } ?: "tcp"
        } else {
            link.params["type"]?.ifBlank { null } ?: "tcp"
        }
        val security = effectiveSecurity(link)

        val stream = JSONObject()
            .put("network", if (network == "none") "tcp" else network)
            .put("security", security.ifBlank { "none" })

        val sni = link.params["sni"].orEmpty().ifBlank { link.params["hostHeader"].orEmpty() }

        when (security) {
            "tls" -> stream.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", sni.ifBlank { link.host })
                    .put("allowInsecure", false)
                    .apply {
                        link.params["fp"]?.takeIf { it.isNotBlank() }
                            ?.let { put("fingerprint", it) }
                        link.params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                            put("alpn", JSONArray().apply { alpn.split(",").forEach { put(it) } })
                        }
                    }
            )

            "reality" -> stream.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", link.params["fp"] ?: "chrome")
                    .put("publicKey", link.params["pbk"] ?: "")
                    .put("shortId", link.params["sid"] ?: "")
                    .put("spiderX", link.params["spx"] ?: "")
            )
        }

        when (stream.getString("network")) {
            "ws" -> stream.put(
                "wsSettings",
                JSONObject()
                    .put("path", link.params["path"]?.ifBlank { "/" } ?: "/")
                    .put(
                        "headers",
                        JSONObject().put(
                            "Host",
                            link.params["host"].orEmpty()
                                .ifBlank { link.params["hostHeader"].orEmpty() }
                                .ifBlank { link.host }
                        )
                    )
            )

            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put(
                    "serviceName",
                    link.params["serviceName"] ?: link.params["path"] ?: ""
                )
            )

            "http", "h2" -> stream.put(
                "httpSettings",
                JSONObject()
                    .put("path", link.params["path"]?.ifBlank { "/" } ?: "/")
                    .put(
                        "host",
                        JSONArray().put(
                            link.params["host"].orEmpty().ifBlank { link.host }
                        )
                    )
            )
        }

        return stream
    }
}
