package com.antitahrim.azad.warp

import kotlin.random.Random

/**
 * نقاط اتصال WARP.
 *
 * Cloudflare سرویس WARP را روی چند رنج آی‌پی و ده‌ها پورت UDP ارائه می‌دهد.
 * در ایران معمولاً پورت پیش‌فرض (2408) و رنج اصلی محدود یا کند می‌شود ولی
 * بقیه ترکیب‌ها باز می‌مانند. برنامه ترکیب‌ها را امتحان می‌کند تا یکی که
 * واقعاً دست‌دادن می‌دهد پیدا شود. کاربر هیچ کانفیگی وارد نمی‌کند.
 */
object Endpoints {

    /** رنج‌های /24 که Cloudflare برای WARP استفاده می‌کند. */
    private val PREFIXES = listOf(
        "162.159.192", "162.159.193", "162.159.195",
        "188.114.96", "188.114.97", "188.114.98", "188.114.99"
    )

    /** پورت‌های UDP که WARP روی آنها جواب می‌دهد. */
    private val PORTS = intArrayOf(
        500, 854, 859, 864, 878, 880, 890, 891, 894, 903,
        908, 928, 934, 939, 942, 943, 945, 946, 955, 968,
        987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387,
        1701, 1843, 2371, 2408, 2506, 3138, 3476, 3581, 3854, 4177,
        4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559,
        8319, 8742, 8854, 8886
    )

    /** نقاطی که معمولاً کار می‌کنند و اول از همه امتحان می‌شوند. */
    private val WELL_KNOWN = listOf(
        "162.159.192.1:2408",
        "188.114.96.1:2408",
        "162.159.193.10:1701",
        "188.114.98.224:955",
        "162.159.195.1:894"
    )

    /**
     * فهرستی از نقاط برای امتحان می‌سازد.
     * اول نقاط شناخته‌شده، بعد ترکیب‌های تصادفی از رنج‌ها و پورت‌ها.
     */
    fun candidates(count: Int, preferred: String? = null): List<String> {
        val result = LinkedHashSet<String>()
        preferred?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        result.addAll(WELL_KNOWN)

        var guard = 0
        while (result.size < count && guard < count * 40) {
            guard++
            val prefix = PREFIXES[Random.nextInt(PREFIXES.size)]
            val host = Random.nextInt(1, 255)
            val port = PORTS[Random.nextInt(PORTS.size)]
            result.add("$prefix.$host:$port")
        }
        return result.take(count).toList()
    }
}
