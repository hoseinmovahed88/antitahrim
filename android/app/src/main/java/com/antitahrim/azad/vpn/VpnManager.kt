package com.antitahrim.azad.vpn

import android.content.Context
import com.antitahrim.azad.core.Report
import com.antitahrim.azad.net.IranDns
import com.antitahrim.azad.warp.Endpoints
import com.antitahrim.azad.warp.Store
import com.antitahrim.azad.warp.WarpAccount
import com.antitahrim.azad.warp.WarpRegistrar
import com.antitahrim.azad.xray.ConfigSources
import com.antitahrim.azad.xray.ProxyProbe
import com.antitahrim.azad.xray.ServerTester
import com.antitahrim.azad.xray.XrayConfig
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * مغز برنامه. سه کار پشت سر هم انجام می‌دهد و هیچ‌کدام از کاربر ورودی نمی‌خواهد:
 *
 *   ۱. اگر حساب WARP نداریم، یکی می‌سازد (کلیدها روی همین گوشی تولید می‌شوند).
 *   ۲. نقاط اتصال را یکی‌یکی امتحان می‌کند تا یکی پیدا شود که واقعاً
 *      بسته رد و بدل می‌کند. نتیجه ذخیره می‌شود تا دفعه بعد فوری وصل شود.
 *   ۳. تونل را با آن نقطه بالا می‌آورد.
 */
class VpnManager private constructor(context: Context) {

    sealed interface State {
        data object Disconnected : State
        data object Registering : State
        data object Preparing : State
        data class Scanning(val tried: Int, val total: Int, val endpoint: String) : State
        data class Connected(val endpoint: String) : State
        data class Failed(val message: String) : State
    }

    /** نتیجه امتحان یک نقطه اتصال. تفاوت این سه حالت کل تشخیص را می‌سازد. */
    private enum class Probe {
        /** دست‌دادن انجام شد و ترافیک هم رد و بدل شد. */
        WORKING,

        /** دست‌دادن انجام شد ولی ترافیک عبور نکرد. یعنی نقطه زنده است. */
        HANDSHAKE_ONLY,

        /** هیچ پاسخی نیامد. یعنی بسته‌ها اصلاً به مقصد نرسیدند یا برنگشتند. */
        SILENT
    }

    private val appContext = context.applicationContext
    private val store = Store(appContext)
    private val backend: Backend by lazy { GoBackend(appContext) }
    private val tunnel = AzadTunnel { onBackendState(it) }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    val log: StateFlow<List<String>> = Report.lines

    /** چند نقطه اتصال قبل از تسلیم شدن امتحان شود. */
    private val scanBudget = 22

    /** چند سرور Xray با تونل واقعی امتحان شود، نه فقط با غربال TCP. */
    private val fullAttempts = 6

    private fun onBackendState(newState: Tunnel.State) {
        if (newState == Tunnel.State.DOWN && _state.value is State.Connected) {
            Report.log("تونل از بیرون قطع شد")
            _state.value = State.Disconnected
        }
    }

    fun isConnected(): Boolean {
        if (AzadVpnService.state.value is AzadVpnService.State.Connected) return true
        return runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (store.transport == Store.TRANSPORT_XRAY) {
            connectViaXray()
        } else {
            connectViaWarp()
        }
    }

    /**
     * مسیر Xray: فهرست‌های عمومی گرفته می‌شود، سرورها غربال می‌شوند، و
     * اولین سروری که واقعاً ترافیک عبور می‌دهد نگه داشته می‌شود.
     */
    private suspend fun connectViaXray() {
        try {
            _state.value = State.Preparing
            Report.log("شروع اتصال از راه Xray")

            val cached = store.workingEndpoint
            val servers = ConfigSources.fetchAll(store.fragmentTls, store.strictIranDns)
            if (servers.isEmpty()) {
                _state.value = State.Failed(
                    "هیچ فهرست سروری دریافت نشد. اینترنت را بررسی کنید و دوباره بزنید."
                )
                return
            }

            // اگر سروری قبلاً کار کرده بود، اول همان امتحان می‌شود
            val ordered = if (cached != null) {
                servers.sortedByDescending { it.key == cached }
            } else {
                servers
            }

            val ranked = ServerTester.rank(ordered) { tested, total, alive ->
                _state.value = State.Scanning(tested, total, "زنده: " + alive)
            }

            if (ranked.isEmpty()) {
                _state.value = State.Failed(
                    "هیچ‌کدام از سرورهای فهرست از این شبکه در دسترس نبودند."
                )
                return
            }

            // غربال TCP فقط می‌گوید پورت باز است. خیلی از این سرورها
            // پورتشان باز است و دست‌دادن رمزنگاری‌شان شکست می‌خورد، یا کلید
            // و شناسه‌شان منقضی شده. پس سریع‌ترین سرور لزوماً کار نمی‌کند و
            // تکیه بر یک نامزد یعنی شکست کل اتصال با اولین سرور خراب.
            // به جایش چند نامزد اول یکی‌یکی تا آخر امتحان می‌شوند و فقط
            // آنی می‌ماند که ترافیک واقعی از آن عبور کند.
            val attempts = ranked.take(fullAttempts)
            Report.log("امتحان کامل روی " + attempts.size + " نامزد اول")

            attempts.forEachIndexed { index, candidate ->
                _state.value = State.Scanning(index + 1, attempts.size, candidate.link.label)
                Report.log(
                    "نامزد " + (index + 1) + ": " + candidate.link.label +
                        " [" + candidate.ip + "] " + candidate.latencyMs + " میلی‌ثانیه"
                )

                if (tryXrayServer(candidate)) {
                    store.workingEndpoint = candidate.link.key
                    _state.value = State.Connected(candidate.link.label)
                    Report.log("وصل شد به " + candidate.link.label)
                    return
                }

                // پیش از نامزد بعدی، سرویس و هسته باید کامل پایین بیایند
                AzadVpnService.stop(appContext)
                delay(1_200)
            }

            _state.value = State.Failed(
                "از " + attempts.size + " سروری که پورتشان باز بود، هیچ‌کدام ترافیک عبور نداد. " +
                    "دوباره بزنید تا سرورهای تازه امتحان شوند."
            )
        } catch (e: Throwable) {
            Report.logError("اتصال Xray", e)
            _state.value = State.Failed(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
        }
    }


    /**
     * یک سرور را تا آخر امتحان می‌کند: تونل بالا بیاید و ترافیک واقعی از آن
     * عبور کند. اگر هر کدام نشد، false و دلیلش در گزارش.
     */
    private suspend fun tryXrayServer(candidate: ServerTester.Result): Boolean {
        val config = XrayConfig.build(
            candidate.link,
            AzadVpnService.DEFAULT_SOCKS_PORT,
            candidate.ip
        )
        AzadVpnService.start(
            appContext,
            config,
            AzadVpnService.DEFAULT_SOCKS_PORT,
            candidate.link.label
        )

        when (val outcome = awaitTunnel()) {
            is TunnelOutcome.Failed -> {
                Report.log("این نامزد رد شد: " + outcome.message)
                return false
            }

            TunnelOutcome.TimedOut -> {
                Report.log("این نامزد در زمان مقرر تونل نساخت")
                return false
            }

            TunnelOutcome.Established -> Unit
        }

        // بالا آمدن تونل کافی نیست. تا وقتی یک بسته واقعی رفت و برنگردد
        // نمی‌شود گفت وصل شده‌ایم. همان اشتباهی که مسیر WARP از آن در امان
        // بود و اینجا تکرار شده بود.
        return trafficFlows()
    }

    private sealed interface TunnelOutcome {
        data object Established : TunnelOutcome
        data object TimedOut : TunnelOutcome
        data class Failed(val message: String) : TunnelOutcome
    }

    /**
     * صبر می‌کند تا سرویس VPN واقعاً تونل را برقرار کند.
     *
     * راه‌اندازی سرویس با یک intent انجام می‌شود و بلافاصله برمی‌گردد، پس
     * بدون این انتظار، برنامه پیش از آنکه چیزی ساخته شود «متصل» اعلام می‌کرد.
     */
    private suspend fun awaitTunnel(timeoutMs: Long = 20_000): TunnelOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val serviceState = AzadVpnService.state.value) {
                is AzadVpnService.State.Connected -> return TunnelOutcome.Established
                is AzadVpnService.State.Failed -> return TunnelOutcome.Failed(serviceState.message)
                else -> delay(400)
            }
        }
        return TunnelOutcome.TimedOut
    }

    /**
     * اثبات اینکه ترافیک واقعاً تا سرور می‌رود و برمی‌گردد.
     *
     * یک درخواست از خود برنامه چیزی ثابت نمی‌کند، چون برنامه عمداً از VPN
     * کنار گذاشته شده و درخواستش از کنار تونل رد می‌شود. به جایش مستقیم از
     * پروکسی SOCKS محلی عبور داده می‌شود، که روی لوپ‌بک است و زنجیره
     * Xray تا سرور را واقعاً می‌سنجد.
     */
    private suspend fun trafficFlows(): Boolean {
        // دو تلاش و نه بیشتر. هر تلاش ناموفق چند ثانیه می‌گیرد و چند نامزد
        // پشت سر این صف ایستاده‌اند؛ سروری که دو بار جواب نداد را رها
        // می‌کنیم و وقت را روی نامزد بعدی می‌گذاریم.
        repeat(2) { attempt ->
            if (ProxyProbe.trafficFlows(AzadVpnService.DEFAULT_SOCKS_PORT)) {
                Report.log("ترافیک از سرور عبور کرد")
                return true
            }
            if (attempt < 1) delay(1_000)
        }
        Report.log("تونل بالا آمد ولی ترافیکی از سرور عبور نکرد")
        return false
    }

    private suspend fun connectViaWarp() {
        // Throwable و نه Exception. اگر کتابخانه بومی WireGuard بارگذاری نشود
        // یک UnsatisfiedLinkError می‌آید که از نوع Error است، نه Exception، و
        // با catch محدود به Exception بی‌صدا از برنامه بیرون می‌زند.
        try {
            _state.value = State.Preparing
            Report.log("شروع اتصال از راه WARP")

            val account = ensureAccount()

            val cached = store.workingEndpoint
            if (cached != null) {
                Report.log("امتحان آخرین نقطه موفق: " + cached)
                if (tryEndpoint(account, cached) == Probe.WORKING) {
                    finishConnected(account, cached)
                    return
                }
                Report.log("آن نقطه دیگر جواب نمی‌دهد، جست‌وجوی دوباره")
                store.workingEndpoint = null
            }

            val candidates = Endpoints.candidates(scanBudget)
            Report.log("جست‌وجو بین " + candidates.size + " نقطه، IPv6: " + Endpoints.hasGlobalIpv6())

            var handshakes = 0
            candidates.forEachIndexed { index, endpoint ->
                _state.value = State.Scanning(index + 1, candidates.size, endpoint)
                when (tryEndpoint(account, endpoint)) {
                    Probe.WORKING -> {
                        store.workingEndpoint = endpoint
                        finishConnected(account, endpoint)
                        return
                    }

                    Probe.HANDSHAKE_ONLY -> handshakes++
                    Probe.SILENT -> Unit
                }
            }

            stopTunnel()
            _state.value = State.Failed(diagnose(handshakes, candidates.size))
        } catch (e: Throwable) {
            stopTunnel()
            Report.logError("اتصال", e)
            _state.value = State.Failed(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
        }
    }

    /**
     * وقتی هیچ نقطه‌ای کار نکرد، تعداد دست‌دادن‌های موفق تعیین می‌کند که
     * مشکل کجاست. این تفاوت مهم است و تعیین می‌کند قدم بعدی چیست.
     */
    private fun diagnose(handshakes: Int, total: Int): String = if (handshakes > 0) {
        Report.log("نتیجه: " + handshakes + " نقطه دست‌دادند ولی ترافیک عبور نکرد")
        "تونل برقرار شد ولی ترافیک عبور نکرد. اپراتور بسته‌های WARP را عبور می‌دهد ولی محدود می‌کند."
    } else {
        Report.log("نتیجه: هیچ‌کدام از " + total + " نقطه حتی دست‌دادن هم نکردند")
        "هیچ‌کدام از نقاط اتصال حتی پاسخ اولیه هم ندادند. یعنی اپراتور پروتکل WARP را مسدود کرده، نه فقط یک آی‌پی را."
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        stopTunnel()
        AzadVpnService.stop(appContext)
        _state.value = State.Disconnected
        Report.log("قطع شد")
    }

    /** حساب موجود را برمی‌دارد یا یکی تازه می‌سازد. */
    private fun ensureAccount(): WarpAccount {
        store.account?.let {
            Report.log("حساب موجود استفاده شد")
            return it
        }

        _state.value = State.Registering
        Report.log("ساخت حساب رایگان روی همین دستگاه")
        val registrar = WarpRegistrar(
            fragmentTls = store.fragmentTls,
            strictIranDns = store.strictIranDns
        )
        val account = registrar.register()
        store.account = account
        Report.log("حساب ساخته شد، آدرس داخلی " + account.addressV4)
        return account
    }

    /**
     * تونل را با این نقطه بالا می‌آورد و دو چیز جدا را می‌سنجد.
     *
     * اول دست‌دادن WireGuard: اگر انجام شود یعنی بسته‌های ما به Cloudflare
     * رسیده و پاسخش برگشته. این تنها سنجه‌ای است که ثابت می‌کند مسیر باز است.
     *
     * بعد عبور واقعی ترافیک با یک پرس‌وجوی DNS. صرف بالا آمدن تونل معنایی
     * ندارد؛ WireGuard وقتی مقصد اصلاً جواب نمی‌دهد هم «بالا» به نظر می‌رسد.
     */
    private suspend fun tryEndpoint(account: WarpAccount, endpoint: String): Probe {
        val config = runCatching {
            ConfigBuilder.build(account, endpoint, domesticDirect = false)
        }.getOrElse {
            Report.logError("ساخت کانفیگ برای " + endpoint, it)
            return Probe.SILENT
        }

        runCatching { backend.setState(tunnel, Tunnel.State.UP, config) }.onFailure {
            Report.logError("بالا آوردن تونل روی " + endpoint, it)
            return Probe.SILENT
        }

        val peerKey = runCatching { Key.fromBase64(account.peerPublicKey) }.getOrNull()
        var sawHandshake = false

        // شش دور، هر دور یک ثانیه و نیم. WireGuard هر پنج ثانیه دست‌دادن را
        // تکرار می‌کند، پس این پنجره دو تلاش کامل را پوشش می‌دهد.
        repeat(6) {
            delay(1_500)

            if (!sawHandshake && peerKey != null && handshakeHappened(peerKey)) {
                sawHandshake = true
                Report.log(endpoint + " دست‌دادن انجام شد")
            }

            if (sawHandshake && IranDns.probeThroughTunnel()) {
                Report.log(endpoint + " ترافیک عبور کرد")
                return Probe.WORKING
            }
        }

        stopTunnel()
        return if (sawHandshake) {
            Report.log(endpoint + " دست‌دادن شد ولی ترافیک عبور نکرد")
            Probe.HANDSHAKE_ONLY
        } else {
            Report.log(endpoint + " بی‌پاسخ")
            Probe.SILENT
        }
    }

    /**
     * آیا WireGuard با همتا دست داده است؟
     *
     * این را مستقیم از خود هسته می‌پرسیم. زمان آخرین دست‌دادن صفر نباشد یعنی
     * بسته رمزنگاری‌شده ما به Cloudflare رسیده و پاسخ امضاشده‌اش برگشته.
     * هیچ راه دیگری برای جعل این وجود ندارد.
     */
    private fun handshakeHappened(peerKey: Key): Boolean = runCatching {
        val stats = backend.getStatistics(tunnel)
        (stats.peer(peerKey)?.latestHandshakeEpochMillis() ?: 0L) > 0L
    }.getOrDefault(false)

    /**
     * وقتی نقطه‌ای جواب داد، اگر کاربر «ترافیک داخلی مستقیم» را خواسته باشد
     * تونل با مسیرهای کامل دوباره ساخته می‌شود. جست‌وجو عمداً با مسیر ساده
     * انجام می‌شود چون فهرست بلند مسیرها هر بار بالا آمدن را کند می‌کند.
     */
    private fun finishConnected(account: WarpAccount, endpoint: String) {
        if (store.domesticDirect) {
            Report.log("اعمال مسیر مستقیم برای سایت‌های ایرانی")
            val full = runCatching {
                ConfigBuilder.build(account, endpoint, domesticDirect = true)
            }.getOrNull()
            if (full != null) {
                runCatching { backend.setState(tunnel, Tunnel.State.UP, full) }.onFailure {
                    Report.logError("اعمال مسیر مستقیم", it)
                }
            }
        }
        _state.value = State.Connected(endpoint)
        Report.log("وصل شد به " + endpoint)
    }

    private fun stopTunnel() {
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
    }

    fun prefs(): Store = store

    companion object {
        @Volatile
        private var instance: VpnManager? = null

        fun get(context: Context): VpnManager =
            instance ?: synchronized(this) {
                instance ?: VpnManager(context).also { instance = it }
            }
    }
}
