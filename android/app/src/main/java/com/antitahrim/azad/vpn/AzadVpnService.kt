package com.antitahrim.azad.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import azadcore.Azadcore
import com.antitahrim.azad.R
import com.antitahrim.azad.core.Report
import com.antitahrim.azad.warp.Store
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
 *
 * سرویس از همان لحظه شروع جست‌وجو پیش‌زمینه می‌شود، نه از لحظه وصل شدن.
 * جست‌وجو تا دو دقیقه طول می‌کشد و اگر کاربر در این فاصله از برنامه بیرون
 * برود، بدون سرویس پیش‌زمینه اندروید آزاد است کل فرایند را بکشد.
 */
class AzadVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                running = true
                if (wantsForeground(intent)) {
                    goForeground(getString(R.string.state_scanning), connected = false)
                }
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                running = false
                shutdown()
                leaveForeground()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_NOTIFICATION -> {
                if (wantsForeground(intent)) {
                    goForeground(lastText ?: getString(R.string.state_scanning), lastConnected)
                } else {
                    leaveForeground()
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // کانفیگ از راه یک فیلد ایستا می‌آید نه از extra خود intent.
                // با فهرست سایت‌های ایرانی کانفیگ به حدود یک مگابایت می‌رسد،
                // و intent از مرز Binder رد می‌شود که سقفش همین حدود است؛
                // نتیجه‌اش TransactionTooLargeException بود. سرویس در همان
                // فرایند است، پس فیلد ایستا بی‌خطر است.
                val config = pendingConfig
                pendingConfig = null
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_SOCKS_PORT)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

                // پیش از هر بررسی دیگری. سرویسی که با startForegroundService
                // شروع شده باید ظرف چند ثانیه startForeground را صدا بزند،
                // وگرنه اندروید کل برنامه را با استثنایی می‌کشد که هیچ‌جا
                // گرفتنی نیست؛ حتی اگر بعدش بلافاصله کار را رها کنیم.
                running = true
                if (wantsForeground(intent)) {
                    goForeground(getString(R.string.state_connecting_to, label), connected = false)
                }

                if (config.isNullOrBlank()) {
                    _state.value = State.Failed("کانفیگ خالی بود")
                    return START_NOT_STICKY
                }

                // بالا آوردن هسته چند ثانیه طول می‌کشد. روی نخ اصلی
                // انجامش دادن یعنی ANR، پس به یک نخ جدا می‌رود.
                Thread { startTunnel(config, socksPort, label) }.start()
                return START_NOT_STICKY
            }
        }

        // اندروید سرویس را بدون intent دوباره ساخته، یعنی فرایند قبلاً کشته
        // شده و تونلی در کار نیست. چیزی برای ادامه دادن نمانده.
        if (intent == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        shutdown()
        super.onDestroy()
    }

    private fun wantsForeground(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_FOREGROUND, true)

    /** وقتی کاربر VPN را از تنظیمات اندروید قطع کند اینجا خبردار می‌شویم. */
    override fun onRevoke() {
        Report.log("کاربر اجازه VPN را پس گرفت")
        running = false
        shutdown()
        leaveForeground()
        _state.value = State.Revoked
        super.onRevoke()
        stopSelf()
    }

    private fun goForeground(text: String, connected: Boolean) {
        lastText = text
        lastConnected = connected
        val notification = Notifications.ongoing(this, text, connected)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.ONGOING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifications.ONGOING_ID, notification)
            }
        }.onFailure {
            // بدون پیش‌زمینه هم تونل کار می‌کند، فقط در برابر کشته شدن
            // محافظت کمتری دارد. ارزش از کار انداختن اتصال را ندارد.
            Report.logError("پیش‌زمینه کردن سرویس", it)
        }
    }

    private fun leaveForeground() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun startTunnel(config: String, socksPort: Int, label: String) {
        shutdown()
        _state.value = State.Starting

        Report.log("ساخت تونل، طول کانفیگ " + config.length)
        val descriptor = try {
            Azadcore.startXray(config)
            Report.log("هسته Xray بالا آمد، نسخه " + Azadcore.xrayVersion())
            establishTunnel()
        } catch (e: Throwable) {
            Report.logError("بالا آوردن هسته", e)
            runCatching { Azadcore.stopXray() }
            _state.value = State.Failed(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
            return
        }

        if (descriptor == null) {
            runCatching { Azadcore.stopXray() }
            // establish فقط وقتی null می‌دهد که اجازه VPN نداشته باشیم یا
            // برنامه دیگری تونل را در دست گرفته باشد.
            _state.value = State.Failed(
                "اندروید اجازه ساخت تونل نداد. اگر VPN دیگری روشن است خاموشش کنید."
            )
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
            return
        }

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

        // گرفتن IPv6 هم لازم است، وگرنه روی گوشی‌هایی که اپراتور IPv6 داده
        // هر برنامه‌ای که IPv6 را ترجیح بدهد کلاً از کنار تونل رد می‌شود و
        // مستقیم به مقصد فیلترشده می‌رود. از بیرون این دقیقاً شبیه «وصل است
        // ولی اینترنت ندارد» دیده می‌شود.
        runCatching {
            builder.addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
            builder.addRoute("::", 0)
        }.onFailure { Report.logError("گرفتن مسیر IPv6", it) }

        // بدون این، ترافیک خود Xray دوباره وارد tun می‌شود و حلقه می‌سازد
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Report.logError("کنار گذاشتن خود برنامه از VPN", it) }

        return builder.establish()
    }

    private fun shutdown() {
        runCatching { Azadcore.stop() }
        runCatching { Azadcore.stopXray() }
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Connected(val label: String) : State
        data class Failed(val message: String) : State

        /** کاربر از تنظیمات اندروید VPN را قطع کرد یا برنامه دیگری گرفتش. */
        data object Revoked : State
    }

    companion object {
        private const val ACTION_PREPARE = "com.antitahrim.azad.PREPARE"
        private const val ACTION_START = "com.antitahrim.azad.START"
        private const val ACTION_STOP = "com.antitahrim.azad.STOP"
        private const val ACTION_NOTIFICATION = "com.antitahrim.azad.NOTIFICATION"
        private const val EXTRA_SOCKS_PORT = "socks_port"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_FOREGROUND = "foreground"

        const val DEFAULT_SOCKS_PORT = 10808
        private const val MTU = 1500
        private const val TUN_ADDRESS = "10.28.0.2"
        private const val TUN_PREFIX = 30
        private const val TUN_ADDRESS_V6 = "fd00:2026:a2ad::1"
        private const val TUN_PREFIX_V6 = 128
        private const val SESSION_NAME = "Azad"

        @Volatile
        private var pendingConfig: String? = null

        /** سرویس در حال کار است، چه در جست‌وجو و چه وصل. */
        @Volatile
        private var running = false

        /** آخرین متن اعلان، تا اگر کاربر اعلان را دوباره روشن کرد همان را ببیند. */
        @Volatile
        private var lastText: String? = null

        @Volatile
        private var lastConnected = false

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        /**
         * سرویس را پیش‌زمینه می‌کند بی‌آنکه تونلی بسازد. در شروع جست‌وجو
         * صدا زده می‌شود تا فرایند در طول جست‌وجو هم در امان باشد.
         */
        fun prepare(context: Context) {
            val intent = Intent(context, AzadVpnService::class.java).setAction(ACTION_PREPARE)
            launch(context, intent)
        }

        fun start(context: Context, config: String, socksPort: Int, label: String) {
            _state.value = State.Starting
            pendingConfig = config
            val intent = Intent(context, AzadVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_LABEL, label)
            }
            launch(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AzadVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** متن اعلان را عوض می‌کند، مثلاً وقتی به سرور دیگری جابه‌جا شدیم. */
        fun updateNotification(context: Context, text: String, connected: Boolean) {
            lastText = text
            lastConnected = connected
            if (!Store(context).persistentNotification) return
            Notifications.refresh(context, text, connected)
        }

        /**
         * کلید «اعلان همیشگی» را روی سرویسی که همین الان کار می‌کند اعمال
         * می‌کند. اگر سرویسی در کار نباشد چیزی برای عوض کردن نیست؛ تنظیم
         * از اتصال بعدی اثر می‌کند.
         */
        fun applyNotificationSetting(context: Context, enabled: Boolean) {
            if (!running) return
            val intent = Intent(context, AzadVpnService::class.java)
                .setAction(ACTION_NOTIFICATION)
                .putExtra(EXTRA_FOREGROUND, enabled)
            runCatching {
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Report.logError("اعمال تنظیم اعلان", it) }
        }

        /**
         * سرویس را با توجه به تنظیم اعلان شروع می‌کند.
         *
         * با اعلان: startForegroundService، که از اندروید ۸ تنها راه مجاز
         * شروع سرویس از پس‌زمینه است، مثلاً وقتی کاشی اتصال را شروع می‌کند.
         *
         * بدون اعلان: startService معمولی. اگر اندروید آن را رد کند چون
         * برنامه در پس‌زمینه است، ناچار به حالت پیش‌زمینه برمی‌گردیم؛ بدون
         * آن، سرویس اصلاً بالا نمی‌آمد و اتصال بی‌صدا شکست می‌خورد.
         */
        private fun launch(context: Context, intent: Intent) {
            val foreground = Store(context).persistentNotification
            intent.putExtra(EXTRA_FOREGROUND, foreground)

            if (foreground || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }.onFailure { Report.logError("شروع سرویس", it) }
                return
            }

            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Report.log("شروع بدون اعلان از پس‌زمینه مجاز نبود، با اعلان شروع شد")
                intent.putExtra(EXTRA_FOREGROUND, true)
                runCatching { context.startForegroundService(intent) }
                    .onFailure { Report.logError("شروع سرویس", it) }
            }
        }
    }
}
