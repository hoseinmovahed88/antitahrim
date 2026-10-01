package com.antitahrim.azad.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.antitahrim.azad.MainActivity
import com.antitahrim.azad.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * کاشی «آزاد» در پنل تنظیمات سریع، همان جایی که کلید وای‌فای و بلوتوث هست.
 *
 * کاربر یک بار از پنل پایین‌کشیدنی «ویرایش» را می‌زند و این کاشی را اضافه
 * می‌کند. از آن به بعد قطع و وصل بدون باز کردن برنامه است.
 */
class AzadTile : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val current = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = current
        val manager = VpnManager.get(applicationContext)
        watcher = current.launch {
            manager.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        scope?.cancel()
        watcher = null
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val manager = VpnManager.get(applicationContext)

        if (manager.isBusyOrConnected()) {
            AzadControl.disconnect(applicationContext)
            return
        }

        // بدون اجازه VPN نمی‌شود وصل شد، و اجازه فقط از داخل یک صفحه گرفته
        // می‌شود. پس پنل بسته و برنامه باز می‌شود.
        if (VpnService.prepare(applicationContext) != null) {
            openApp()
            return
        }
        AzadControl.connect(applicationContext)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: VpnManager.State) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)

        when (state) {
            is VpnManager.State.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, getString(R.string.state_connected))
            }

            is VpnManager.State.Disconnected,
            is VpnManager.State.Failed -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, getString(R.string.state_disconnected))
            }

            else -> {
                // در حال اتصال: روشن نشان داده می‌شود تا زدنش «لغو» معنا بدهد
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(tile, getString(R.string.state_connecting_short))
            }
        }
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
    }
}
