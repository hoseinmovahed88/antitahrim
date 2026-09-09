package com.antitahrim.azad.warp

import java.net.Inet6Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * نقاط اتصال WARP.
 *
 * Cloudflare سرویس WARP را روی چند رنج آی‌پی و ده‌ها پورت UDP ارائه می‌دهد.
 * در ایران پورت پیش‌فرض (2408) معمولاً محدود شده ولی بعضی ترکیب‌های دیگر
 * باز می‌مانند. برنامه ترکیب‌ها را امتحان می‌کند تا یکی که واقعاً دست‌دادن
 * می‌دهد پیدا شود. کاربر هیچ کانفیگی وارد نمی‌کند.
 */
object Endpoints {

    /** رنج‌های /24 که Cloudflare برای WARP استفاده می‌کند. */
    private val PREFIXES = listOf(
        "162.159.192", "162.159.193", "162.159.195",
        "188.114.96", "188.114.97", "188.114.98", "188.114.99"
    )

    /**
     * پورت‌هایی که بیشترین شانس عبور از بازرسی را دارند، چون ترافیک روی آنها
     * شبیه پروتکل‌های رایج VPN شرکتی است و معمولاً باز گذاشته می‌شوند.
     * اینها اول از همه امتحان می‌شوند.
     */
    private val PRIORITY_PORTS = intArrayOf(500, 4500, 1701, 2408, 8854, 894)

    /** بقیه پورت‌هایی که WARP روی آنها جواب می‌دهد. */
    private val OTHER_PORTS = intArrayOf(
        854, 859, 864, 878, 880, 890, 891, 903,
        908, 928, 934, 939, 942, 943, 945, 946, 955, 968,
        987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387,
        1843, 2371, 2506, 3138, 3476, 3581, 3854, 4177,
        4198, 4233, 5279, 5956, 7103, 7152, 7156, 7281, 7559,
        8319, 8742, 8886
    )

    /**
     * آدرس‌های IPv6 رسمی WARP.
     *
     * چرا مهم است: بازرسی بسته در ایران روی IPv4 دقیق‌تر عمل می‌کند و مسیر
     * IPv6 در بعضی اپراتورها کمتر دستکاری می‌شود. اگر گوشی IPv6 داشته باشد
     * ارزش امتحان دارد.
     */
    private val IPV6_HOSTS = listOf(
        "2606:4700:d0::a29f:c001",
        "2606:4700:d1::a29f:c001",
        "2606:4700:d0::a29f:c101",
        "2606:4700:d1::a29f:c101"
    )

    /** نقاطی که معمولاً کار می‌کنند و اول از همه امتحان می‌شوند. */
    private val WELL_KNOWN = listOf(
        "162.159.192.1:2408",
        "188.114.96.1:500",
        "162.159.193.10:1701",
        "188.114.98.224:4500",
        "162.159.195.1:894",
        "188.114.97.66:955"
    )

    /**
     * فهرستی از نقاط برای امتحان می‌سازد.
     *
     * ترتیب: آخرین نقطه موفق، بعد نقاط شناخته‌شده، بعد IPv6 اگر گوشی داشته
     * باشد، بعد ترکیب‌های تصادفی که پورت‌های پرشانس در آنها وزن بیشتری دارند.
     */
    fun candidates(count: Int, preferred: String? = null): List<String> {
        val result = LinkedHashSet<String>()
        preferred?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        result.addAll(WELL_KNOWN)

        if (hasGlobalIpv6()) {
            for (host in IPV6_HOSTS) {
                result.add("[$host]:2408")
                result.add("[$host]:500")
            }
        }

        var guard = 0
        while (result.size < count && guard < count * 40) {
            guard++
            val prefix = PREFIXES[Random.nextInt(PREFIXES.size)]
            val host = Random.nextInt(1, 255)
            // دو سوم مواقع از پورت‌های پرشانس استفاده می‌شود
            val port = if (Random.nextInt(3) < 2) {
                PRIORITY_PORTS[Random.nextInt(PRIORITY_PORTS.size)]
            } else {
                OTHER_PORTS[Random.nextInt(OTHER_PORTS.size)]
            }
            result.add("$prefix.$host:$port")
        }
        return result.take(count).toList()
    }

    /**
     * آیا این گوشی آدرس IPv6 عمومی دارد؟
     * بدون آن، امتحان کردن نقاط IPv6 فقط وقت تلف کردن است.
     */
    fun hasGlobalIpv6(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            nif.isUp && !nif.isLoopback && nif.inetAddresses.toList().any { address ->
                address is Inet6Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isAnyLocalAddress
            }
        }
    }.getOrDefault(false)
}
