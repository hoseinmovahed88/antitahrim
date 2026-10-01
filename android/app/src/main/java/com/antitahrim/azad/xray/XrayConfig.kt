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
     */
    fun build(
        link: ConfigLink,
        socksPort: Int,
        serverIp: String? = null,
        iranDirect: IranList.Lists? = null
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

        root.put(
            "outbounds",
            JSONArray()
                .put(outbound(link, serverIp))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        )

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules(iranDirect))
        )

        return root.toString()
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

        // مقصدهای محلی هیچ‌وقت نباید وارد تونل شوند، وگرنه حلقه می‌سازند
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("ip", JSONArray().apply { PRIVATE_RANGES.forEach { put(it) } })
                .put("outboundTag", "direct")
        )

        if (iranDirect == null || iranDirect.isEmpty) return rules

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

        return rules
    }

    private fun outbound(link: ConfigLink, serverIp: String?): JSONObject {
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", link.protocol)
            .put("settings", settings(link, serverIp ?: link.host))
            .put("streamSettings", streamSettings(link))
        return outbound
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
        val network = link.params["type"] ?: link.params["net"] ?: "tcp"
        val security = link.params["security"].orEmpty().let {
            // vmess تنها مقدار "tls" را می‌گذارد و گاهی خالی است
            if (it == "true") "tls" else it
        }

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
