package com.antitahrim.azad.warp

import com.antitahrim.azad.net.FragmentConfig
import com.antitahrim.azad.net.Http
import com.antitahrim.azad.net.IranDns
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Date

/**
 * حساب رایگان WARP را خودکار روی دستگاه می‌سازد.
 *
 * این همان کاری است که خود برنامه رسمی Cloudflare هنگام نصب می‌کند:
 * یک جفت کلید تولید می‌شود، کلید عمومی ثبت می‌شود، و سرور آدرس داخلی و
 * کلید عمومی همتا را برمی‌گرداند. نه حساب کاربری لازم است، نه پرداخت،
 * نه سرور شخصی، نه کانفیگ دستی.
 */
class WarpRegistrar(
    private val fragmentTls: Boolean = true,
    private val strictIranDns: Boolean = false
) {

    private val host = "api.cloudflareclient.com"
    private val apiVersion = "v0a2158"

    private val fragment: FragmentConfig
        get() = FragmentConfig(enabled = fragmentTls)

    private val commonHeaders = mapOf(
        "Content-Type" to "application/json; charset=UTF-8",
        "User-Agent" to "okhttp/3.12.1",
        "CF-Client-Version" to "a-6.10-2158",
        "Accept" to "application/json"
    )

    fun register(): WarpAccount {
        val keys = KeyPair()
        val privateKey = keys.privateKey.toBase64()
        val publicKey = keys.publicKey.toBase64()

        val addresses = IranDns.resolve(host, allowSystemFallback = !strictIranDns)
        if (addresses.isEmpty()) {
            throw IOException("نام $host با DNS ایرانی پیدا نشد")
        }

        val body = JSONObject().apply {
            put("key", publicKey)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", nowIso())
            put("model", "PC")
            put("serial_number", "")
            put("locale", "en_US")
        }.toString()

        val response = Http.request(
            method = "POST",
            host = host,
            path = "/$apiVersion/reg",
            headers = commonHeaders,
            body = body,
            fragment = fragment,
            resolved = addresses
        )

        if (response.code !in 200..299) {
            throw IOException("ثبت‌نام WARP رد شد (کد ${response.code})")
        }

        val json = JSONObject(response.body)
        val deviceId = json.optString("id")
        val token = json.optString("token")
        val config = json.getJSONObject("config")

        val interfaceAddresses = config.getJSONObject("interface").getJSONObject("addresses")
        val v4 = interfaceAddresses.optString("v4", "172.16.0.2")
        val v6 = interfaceAddresses.optString("v6", "")

        val peers = config.optJSONArray("peers")
        val peerKey = if (peers != null && peers.length() > 0) {
            peers.getJSONObject(0).optString("public_key", WarpAccount.DEFAULT_PEER_KEY)
        } else {
            WarpAccount.DEFAULT_PEER_KEY
        }

        val account = WarpAccount(
            privateKey = privateKey,
            publicKey = publicKey,
            peerPublicKey = peerKey,
            addressV4 = v4,
            addressV6 = v6,
            deviceId = deviceId,
            token = token
        )

        // فعال کردن WARP روی حساب. اگر این مرحله شکست بخورد حساب باز هم
        // معمولاً کار می‌کند، پس جلوی ادامه کار را نمی‌گیریم.
        runCatching { enableWarp(account, addresses) }

        return account
    }

    private fun enableWarp(account: WarpAccount, addresses: List<java.net.InetAddress>) {
        if (account.deviceId.isBlank() || account.token.isBlank()) return
        Http.request(
            method = "PATCH",
            host = host,
            path = "/$apiVersion/reg/${account.deviceId}",
            headers = commonHeaders + mapOf("Authorization" to "Bearer ${account.token}"),
            body = JSONObject().put("warp_enabled", true).toString(),
            fragment = fragment,
            resolved = addresses
        )
    }

    private fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
