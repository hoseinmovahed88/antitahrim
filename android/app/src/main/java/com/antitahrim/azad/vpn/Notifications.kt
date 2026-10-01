package com.antitahrim.azad.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.antitahrim.azad.MainActivity
import com.antitahrim.azad.R

/**
 * اعلان همیشگی تونل، و کلید قطع و وصلش در نوار وضعیت.
 *
 * دو کار جدا با یک تیر: کاربر بدون باز کردن برنامه قطع و وصل می‌کند، و
 * مهم‌تر از آن سرویس «پیش‌زمینه» می‌شود. اندروید سرویس پس‌زمینه را هر وقت
 * حافظه کم بیاید می‌کشد و تونل بی‌خبر قطع می‌شود؛ سرویس پیش‌زمینه با اعلان
 * دیده‌شدنی این امان را می‌گیرد.
 */
object Notifications {

    const val CHANNEL_ID = "azad_tunnel"
    const val ONGOING_ID = 1

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // اهمیت کم: بدون صدا و بدون لرزش. این اعلان خبر نیست، وضعیت است.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_tunnel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_tunnel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * @param text خط دومی که وضعیت لحظه‌ای را می‌گوید
     * @param connected کلید باید «قطع» نشان دهد یا «اتصال»
     */
    fun ongoing(context: Context, text: String, connected: Boolean): Notification {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val action = if (connected) AzadControl.ACTION_DISCONNECT else AzadControl.ACTION_CONNECT
        val toggle = PendingIntent.getBroadcast(
            context,
            if (connected) 1 else 2,
            Intent(context, AzadControl::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = context.getString(if (connected) R.string.disconnect else R.string.connect)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, toggleLabel, toggle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** اعلان را بدون دست زدن به خود سرویس تازه می‌کند. */
    fun refresh(context: Context, text: String, connected: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(ONGOING_ID, ongoing(context, text, connected)) }
    }
}
