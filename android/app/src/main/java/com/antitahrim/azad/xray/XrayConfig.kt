package com.antitahrim.azad.xray

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
     * @param socksPort پورتی که پل tun2socks به آن وصل می‌شود
     * @param link سروری که ترافیک از آن بیرون می‌رود
     */
    fun build(link: ConfigLink, socksPort: Int): String {
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
                .put(outbound(link))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        )

        // مقصدهای محلی هیچ‌وقت نباید وارد تونل شوند، وگرنه حلقه می‌سازند
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put("ip", JSONArray().put("geoip:private"))
                            .put("outboundTag", "direct")
                    )
                )
        )

        return root.toString()
    }

    private fun outbound(link: ConfigLink): JSONObject {
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", link.protocol)
            .put("settings", settings(link))
            .put("streamSettings", streamSettings(link))
        return outbound
    }

    private fun settings(link: ConfigLink): JSONObject = when (link.protocol) {
        "vless" -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", link.host)
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
                    .put("address", link.host)
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
                    .put("address", link.host)
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
