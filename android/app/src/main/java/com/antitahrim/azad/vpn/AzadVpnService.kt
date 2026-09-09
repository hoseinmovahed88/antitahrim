package com.antitahrim.azad.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import azadcore.Azadcore
import com.antitahrim.azad.core.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * سرویس VPN برای مسیر Xray.
 *
 * جریان کار:
 *   ۱. یک دستگاه tun ساخته می‌شود و همه مسیرها به آن می‌رود.
 *   ۲. هسته Xray با کانفیگ سرور انتخابی بالا می‌آید و یک پروکسی SOCKS
 *      روی 127.0.0.1 باز می‌کند.
 *   ۳. پل tun2socks بسته‌های خام tun را به آن پروکسی می‌سپارد.
 *
 * حلقه‌ای که باید مواظبش بود: سوکت‌های خروجی خود Xray نباید دوباره وارد
 * همین tun شوند وگرنه ترافیک بی‌نهایت دور خودش می‌چرخد. به جای پاس دادن یک
 * callback از Go به جاوا برای protect کردن هر سوکت، کل این برنامه از VPN
 * کنار گذاشته می‌شود. ساده‌تر است و جای اشتباه ندارد.
 */
class AzadVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_SOCKS_PORT)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                if (config.isNullOrBlank()) {
                    _state.value = State.Failed("کانفیگ خالی بود")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTunnel(config, socksPort, label)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** وقتی کاربر VPN را از تنظیمات اندروید قطع کند اینجا خبردار می‌شویم. */
    override fun onRevoke() {
        Report.log("کاربر اجازه VPN را پس گرفت")
        shutdown()
        super.onRevoke()
    }

    private fun startTunnel(config: String, socksPort: Int, label: String) {
        shutdown()
        _state.value = State.Starting

        val descriptor = try {
            Azadcore.startXray(config)
            Report.log("هسته Xray بالا آمد، نسخه " + Azadcore.xrayVersion())
            establishTunnel()
        } catch (e: Throwable) {
            Report.logError("بالا آوردن هسته", e)
            runCatching { Azadcore.stopXray() }
            _state.value = State.Failed(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
            stopSelf()
            return
        }

        if (descriptor == null) {
            runCatching { Azadcore.stopXray() }
            _state.value = State.Failed("ساخت دستگاه tun ممکن نشد")
            stopSelf()
            return
        }

        // مالکیت توصیف‌گر به لایه Go می‌رود و خودش هنگام توقف می‌بنددش.
        val rawFd = descriptor.detachFd()
        try {
            Azadcore.start(rawFd.toLong(), socksPort.toLong(), MTU.toLong())
        } catch (e: Throwable) {
            Report.logError("راه‌اندازی پل tun2socks", e)
            // اگر Go نگرفتش، خودمان توصیف‌گر را می‌بندیم تا نشت نکند
            runCatching { ParcelFileDescriptor.adoptFd(rawFd).close() }
            runCatching { Azadcore.stopXray() }
            _state.value = State.Failed(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
            stopSelf()
            return
        }

        tunnel = null
        _state.value = State.Connected(label)
        Report.log("تونل Xray برقرار شد: " + label)
    }

    private fun establishTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // بدون این، ترافیک خود Xray دوباره وارد tun می‌شود و حلقه می‌سازد
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Report.logError("کنار گذاشتن خود برنامه از VPN", it) }

        return builder.establish()
    }

    private fun shutdown() {
        runCatching { Azadcore.stop() }
        runCatching { Azadcore.stopXray() }
        runCatching { tunnel?.close() }
        tunnel = null
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Connected(val label: String) : State
        data class Failed(val message: String) : State
    }

    companion object {
        private const val ACTION_START = "com.antitahrim.azad.START"
        private const val ACTION_STOP = "com.antitahrim.azad.STOP"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_SOCKS_PORT = "socks_port"
        private const val EXTRA_LABEL = "label"

        const val DEFAULT_SOCKS_PORT = 10808
        private const val MTU = 1500
        private const val TUN_ADDRESS = "10.28.0.2"
        private const val TUN_PREFIX = 30
        private const val SESSION_NAME = "Azad"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        fun start(context: Context, config: String, socksPort: Int, label: String) {
            _state.value = State.Starting
            val intent = Intent(context, AzadVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_LABEL, label)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AzadVpnService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
