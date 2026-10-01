package com.antitahrim.azad.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.antitahrim.azad.MainActivity
import com.antitahrim.azad.core.Report

/**
 * نقطه ورود دکمه‌های بیرون از برنامه: دکمه اعلان و کاشی تنظیمات سریع.
 *
 * هر دو از همین‌جا رد می‌شوند تا قطع و وصل فقط یک راه داشته باشد و همان
 * راهی باشد که دکمه داخل برنامه می‌رود.
 */
class AzadControl : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISCONNECT -> disconnect(context)
            ACTION_CONNECT -> connect(context)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.antitahrim.azad.CONNECT"
        const val ACTION_DISCONNECT = "com.antitahrim.azad.DISCONNECT"

        fun disconnect(context: Context) {
            Report.log("قطع از بیرون برنامه")
            VpnManager.get(context).disconnectAsync()
        }

        /**
         * @return false اگر اجازه VPN هنوز داده نشده و برنامه باید باز شود
         *   تا کاربر اجازه بدهد. اجازه را فقط از داخل یک صفحه می‌شود گرفت.
         */
        fun connect(context: Context): Boolean {
            if (VpnService.prepare(context) != null) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            Report.log("اتصال از بیرون برنامه")
            VpnManager.get(context).connectAsync()
            return true
        }
    }
}
