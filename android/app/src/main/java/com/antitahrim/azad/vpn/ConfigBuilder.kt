package com.antitahrim.azad.vpn

import com.antitahrim.azad.net.Cidr
import com.antitahrim.azad.warp.WarpAccount
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer

/** کانفیگ WireGuard را از روی حساب ساخته‌شده و نقطه اتصال انتخابی می‌سازد. */
object ConfigBuilder {

    /** WARP با MTU بزرگ‌تر بسته‌ها را می‌اندازد. */
    private const val MTU = 1280

    /**
     * DNS داخل تونل عمداً Cloudflare است نه DNS ایرانی.
     * دلیلش این است که این پرس‌وجوها داخل تونل رمزنگاری‌شده‌اند و اپراتور
     * نمی‌بیندشان؛ فرستادنشان به یک حل‌کننده داخلی فقط تاریخچه گشت‌وگذار را
     * جای دیگری لو می‌دهد. DNS ایرانی جای دیگری استفاده می‌شود: پیش از
     * برقراری تونل، برای پیدا کردن خود سرور ثبت‌نام.
     */
    private val TUNNEL_DNS = listOf("1.1.1.1", "1.0.0.1")

    fun build(
        account: WarpAccount,
        endpoint: String,
        domesticDirect: Boolean
    ): Config {
        val addresses = buildString {
            append("${account.addressV4}/32")
            if (account.addressV6.isNotBlank()) append(", ${account.addressV6}/128")
        }

        val iface = Interface.Builder()
            .parsePrivateKey(account.privateKey)
            .parseAddresses(addresses)
            .parseDnsServers(TUNNEL_DNS.joinToString(", "))
            .setMtu(MTU)
            .build()

        val peer = Peer.Builder()
            .parsePublicKey(account.peerPublicKey)
            .parseEndpoint(endpoint)
            .parseAllowedIPs(allowedIps(domesticDirect))
            .setPersistentKeepalive(25)
            .build()

        return Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()
    }

    /**
     * وقتی «ترافیک داخلی مستقیم» روشن باشد، به جای 0.0.0.0/0 فهرست
     * «همه جا منهای ایران» ساخته می‌شود. سایت‌های ایرانی از تونل رد نمی‌شوند،
     * پس هم سریع‌تر باز می‌شوند، هم مصرف داخلی حساب می‌شوند، هم Cloudflare
     * چیزی از گشت‌وگذار داخلی شما نمی‌بیند.
     */
    private fun allowedIps(domesticDirect: Boolean): String =
        if (!domesticDirect) {
            "0.0.0.0/0, ::/0"
        } else {
            Cidr.complement(Cidr.IRAN_RANGES).joinToString(", ") + ", ::/0"
        }
}
