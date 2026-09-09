package com.antitahrim.azad.vpn

import com.wireguard.android.backend.Tunnel

/** تونل ساده با یک نام ثابت. تغییر وضعیت را به بیرون خبر می‌دهد. */
class AzadTunnel(private val onState: (Tunnel.State) -> Unit) : Tunnel {
    override fun getName(): String = "azad"
    override fun onStateChange(newState: Tunnel.State) = onState(newState)
}
