package com.antitahrim.azad.xray

import org.json.JSONObject
import java.net.URLDecoder

/**
 * یک سرور از فهرست‌های عمومی.
 *
 * این لینک‌ها با فرمت‌های `vless://`، `vmess://` و `trojan://` منتشر می‌شوند.
 * هر کدام ساختار متفاوتی دارند و اینجا به یک شکل واحد در می‌آیند تا بقیه
 * برنامه لازم نباشد بداند لینک اصلی چه شکلی بوده.
 */
data class ConfigLink(
    val protocol: String,
    val host: String,
    val port: Int,
    val id: String,
    val remark: String,
    val params: Map<String, String>,
    val raw: String
) {
    /** شناسه‌ای پایدار برای همین سرور، تا تکراری‌ها حذف شوند. */
    val key: String get() = "$protocol://$host:$port/$id"

    val label: String
        get() = remark.ifBlank { "$protocol $host:$port" }

    companion object {

        /** یک لینک را می‌خواند. اگر ناشناخته یا خراب بود null برمی‌گرداند. */
        fun parse(line: String): ConfigLink? {
            val trimmed = line.trim()
            return when {
                trimmed.startsWith("vless://") -> parseUrlStyle(trimmed, "vless")
                trimmed.startsWith("trojan://") -> parseUrlStyle(trimmed, "trojan")
                trimmed.startsWith("vmess://") -> parseVmess(trimmed)
                else -> null
            }
        }

        /**
         * فرمت `scheme://id@host:port?params#remark` که vless و trojan
         * هر دو از آن استفاده می‌کنند.
         */
        private fun parseUrlStyle(link: String, protocol: String): ConfigLink? {
            val body = link.removePrefix("$protocol://")
            val remark = body.substringAfter('#', "").let { decode(it) }
            val withoutRemark = body.substringBefore('#')

            val id = withoutRemark.substringBefore('@', "")
            if (id.isBlank()) return null

            val rest = withoutRemark.substringAfter('@', "")
            val hostPort = rest.substringBefore('?')
            val query = rest.substringAfter('?', "")

            // آدرس IPv6 داخل کروشه است و نباید با جداکننده پورت اشتباه شود
            val host: String
            val portText: String
            if (hostPort.startsWith("[")) {
                host = hostPort.substringAfter('[').substringBefore(']')
                portText = hostPort.substringAfterLast("]:", "")
            } else {
                host = hostPort.substringBefore(':')
                portText = hostPort.substringAfter(':', "")
            }
            val port = portText.toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65535) return null

            return ConfigLink(
                protocol = protocol,
                host = host,
                port = port,
                id = id,
                remark = remark,
                params = parseQuery(query),
                raw = link
            )
        }

        /** vmess یک شیء JSON کدشده با base64 است. */
        private fun parseVmess(link: String): ConfigLink? {
            val encoded = link.removePrefix("vmess://").substringBefore('#')
            val json = runCatching {
                JSONObject(String(decodeBase64(encoded), Charsets.UTF_8))
            }.getOrNull() ?: return null

            val host = json.optString("add")
            val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
            val id = json.optString("id")
            if (host.isBlank() || id.isBlank() || port !in 1..65535) return null

            val params = buildMap {
                put("net", json.optString("net", "tcp"))
                put("type", json.optString("type", "none"))
                put("security", json.optString("tls", ""))
                put("sni", json.optString("sni", json.optString("host", "")))
                put("path", json.optString("path", ""))
                put("hostHeader", json.optString("host", ""))
                put("alterId", json.optString("aid", "0"))
                put("scy", json.optString("scy", "auto"))
            }

            return ConfigLink(
                protocol = "vmess",
                host = host,
                port = port,
                id = id,
                remark = json.optString("ps"),
                params = params,
                raw = link
            )
        }


        /**
         * رمزگشای base64 خودمان.
         *
         * چرا نه android.util.Base64: آن کلاس در تست‌های واحد روی JVM موجود
         * نیست و تجزیه لینک دقیقاً همان چیزی است که باید تست شود.
         * java.util.Base64 هم از اندروید ۸ به بعد است و ما اندروید ۷ را
         * پشتیبانی می‌کنیم. هر دو حالت استاندارد و امن‌برای‌URL پذیرفته
         * می‌شود، و بدون padding هم کار می‌کند چون خیلی از لینک‌ها ندارند.
         */
        internal fun decodeBase64(input: String): ByteArray {
            val alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val cleaned = input.trim().replace("-", "+").replace("_", "/")
                .filter { it != '=' && !it.isWhitespace() }

            require(cleaned.isNotEmpty()) { "ورودی base64 خالی است" }

            val out = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4 + 3)
            var buffer = 0
            var bits = 0
            for (ch in cleaned) {
                val value = alphabet.indexOf(ch)
                require(value >= 0) { "کاراکتر نامعتبر در base64: $ch" }
                buffer = (buffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', "")
                if (name.isBlank()) return@mapNotNull null
                name to decode(pair.substringAfter('=', ""))
            }.toMap()
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
