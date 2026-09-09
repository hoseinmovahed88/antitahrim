package com.antitahrim.azad.warp

import android.content.Context
import org.json.JSONObject

/**
 * حسابی که برنامه خودش روی دستگاه می‌سازد.
 * کلید خصوصی هیچ‌وقت از گوشی بیرون نمی‌رود و جایی آپلود نمی‌شود.
 */
data class WarpAccount(
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val addressV4: String,
    val addressV6: String,
    val deviceId: String,
    val token: String
) {
    fun toJson(): String = JSONObject().apply {
        put("private_key", privateKey)
        put("public_key", publicKey)
        put("peer_public_key", peerPublicKey)
        put("address_v4", addressV4)
        put("address_v6", addressV6)
        put("device_id", deviceId)
        put("token", token)
    }.toString()

    companion object {
        /** کلید عمومی سراسری WARP. برای همه یکی است و فقط پشتیبان است. */
        const val DEFAULT_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        fun fromJson(raw: String): WarpAccount? = runCatching {
            val o = JSONObject(raw)
            WarpAccount(
                privateKey = o.getString("private_key"),
                publicKey = o.getString("public_key"),
                peerPublicKey = o.optString("peer_public_key", DEFAULT_PEER_KEY),
                addressV4 = o.optString("address_v4", "172.16.0.2"),
                addressV6 = o.optString("address_v6", ""),
                deviceId = o.optString("device_id", ""),
                token = o.optString("token", "")
            )
        }.getOrNull()
    }
}

/** ذخیره‌سازی محلی. هیچ چیزی به بیرون فرستاده نمی‌شود. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("azad", Context.MODE_PRIVATE)

    var account: WarpAccount?
        get() = prefs.getString(KEY_ACCOUNT, null)?.let { WarpAccount.fromJson(it) }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, value.toJson())
        }.apply()

    /** آخرین نقطه اتصالی که واقعاً جواب داد. */
    var workingEndpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    /** ترافیک مقصدهای ایران مستقیم برود و وارد تونل نشود. */
    var domesticDirect: Boolean
        get() = prefs.getBoolean(KEY_DOMESTIC, false)
        set(value) = prefs.edit().putBoolean(KEY_DOMESTIC, value).apply()

    /** تکه‌تکه کردن دست‌دادن TLS هنگام ثبت‌نام. */
    var fragmentTls: Boolean
        get() = prefs.getBoolean(KEY_FRAGMENT, true)
        set(value) = prefs.edit().putBoolean(KEY_FRAGMENT, value).apply()

    /** فقط از DNS ایرانی استفاده شود و به DNS دستگاه برنگردد. */
    var strictIranDns: Boolean
        get() = prefs.getBoolean(KEY_STRICT_DNS, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_DNS, value).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_DOMESTIC = "domestic_direct"
        const val KEY_FRAGMENT = "fragment_tls"
        const val KEY_STRICT_DNS = "strict_iran_dns"
    }
}
