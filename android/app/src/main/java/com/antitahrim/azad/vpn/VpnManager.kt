package com.antitahrim.azad.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import azadcore.Azadcore
import com.antitahrim.azad.R
import com.antitahrim.azad.core.CoreCrash
import com.antitahrim.azad.core.Report
import com.antitahrim.azad.net.IranDns
import com.antitahrim.azad.net.IranList
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * دامنه کارهای پس‌زمینه، به عمر خود برنامه و نه به عمر صفحه.
     *
     * اتصال تا دو دقیقه طول می‌کشد و نگهبان اتصال تا وقتی وصلیم کار می‌کند.
     * اگر این‌ها به چرخه عمر صفحه گره بخورند، با بستن صفحه یا زدن کاشی از
     * پنل نیمه‌کاره لغو می‌شوند.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null

    /** سرورهایی که در غربال زنده بودند، به ترتیب سرعت. ذخیره برای جابه‌جایی سریع. */
    @Volatile
    private var pool: List<ServerTester.Result> = emptyList()

    /** سروری که الان رویش هستیم. */
    @Volatile
    private var current: ServerTester.Result? = null

    /** چند نقطه اتصال قبل از تسلیم شدن امتحان شود. */
    private val scanBudget = 22

    /** چند سرور Xray با تونل واقعی امتحان شود، نه فقط با غربال TCP. */
    private val fullAttempts = 6

    /** سرورهای اخیراً امتحان‌شده در همین دور، تازه‌ترین اول. */
    private val recentlyTried = ArrayDeque<String>()

    init {
        quarantineAfterCrash()
    }

    /**
     * اگر فرایند وسط امتحان یک سرور مرده باشد، آن سرور مقصر است.
     *
     * بعضی کانفیگ‌های فهرست‌های عمومی هسته را به وحشت می‌اندازند، و وحشت
     * در goroutine هسته کل برنامه را می‌کشد؛ هیچ استثنایی نیست که بشود
     * گرفت. پس همان سرورها کنار گذاشته می‌شوند تا دفعه بعد دوباره همین
     * اتفاق نیفتد.
     *
     * سرور قبلی هم همراهش کنار می‌رود، چون وحشت گاهی یکی دو ثانیه بعد از
     * رها کردن یک سرور رخ می‌دهد، وقتی نوبت سرور بعدی شده. یک سرور سالم
     * که اشتباهی کنار برود از میان صدها سرور هزینه‌ای ندارد؛ یک سرور
     * خراب که دوباره امتحان شود یعنی یک کرش دیگر.
     */
    private fun quarantineAfterCrash() {
        val suspects = store.serversInFlight
        if (suspects.isEmpty()) return
        store.addToQuarantine(suspects)
        store.serversInFlight = emptyList()
        Report.log("برنامه دفعه قبل وسط امتحان سرور بسته شد؛ " + suspects.size + " سرور مظنون کنار گذاشته شد")
        _state.value = State.Failed(
            appContext.getString(R.string.crashed_on_server)
        )
    }

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

    /** وصل است یا در حال وصل شدن. کاشی بر اساس این تصمیم می‌گیرد بزند یا قطع کند. */
    fun isBusyOrConnected(): Boolean = when (_state.value) {
        is State.Disconnected, is State.Failed -> isConnected()
        else -> true
    }

    /** اتصال را در پس‌زمینه شروع می‌کند. اگر از قبل در جریان باشد کاری نمی‌کند. */
    fun connectAsync() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connect() }
    }

    /** هر کار در جریان را لغو و تونل را قطع می‌کند. */
    fun disconnectAsync() {
        scope.launch { disconnect() }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        watchdogJob?.cancel()
        when (store.transport) {
            Store.TRANSPORT_XRAY -> connectViaXray()
            Store.TRANSPORT_BALE -> connectViaBale()
            else -> connectViaWarp()
        }
    }

    /**
     * مسیر تماس بله: به تماسی که Creator بیرون از ایران ساخته می‌پیوندیم،
     * ترافیک را از دل آن رد می‌کنیم، و رابط VPN را روی پروکسی محلی جوینر
     * می‌سازیم. همان پل و همان رابطی که مسیر Xray استفاده می‌کند.
     */
    private suspend fun connectViaBale() {
        try {
            val link = store.baleLink.trim()
            if (link.isEmpty()) {
                _state.value = State.Failed(appContext.getString(R.string.bale_link_missing))
                return
            }

            _state.value = State.Preparing
            Report.log("شروع اتصال از راه تماس بله")
            AzadVpnService.prepare(appContext)

            // فهرست ایران موازی تازه می‌شود؛ اتصال منتظرش نمی‌ماند مگر لازم باشد
            val listsReady = scope.async { refreshIranListIfNeeded() }

            // وحشت هسته Go، از جمله جوینر بله، فرایند را بی‌صدا می‌کشد؛ ردش ثبت شود
            CoreCrash.install(appContext)
            if (!joinBaleCall(link)) return

            listsReady.await()
            AzadVpnService.establish(
                appContext,
                BaleTransport.SOCKS_PORT,
                appContext.getString(R.string.transport_bale),
                excludeIran = store.domesticDirect
            )
            when (val outcome = awaitTunnel()) {
                TunnelOutcome.Established -> Unit
                is TunnelOutcome.Failed -> {
                    failBale(outcome.message)
                    return
                }
                TunnelOutcome.TimedOut -> {
                    failBale("رابط VPN در زمان مقرر ساخته نشد.")
                    return
                }
            }

            val label = appContext.getString(R.string.transport_bale)
            _state.value = State.Connected(label)
            AzadVpnService.updateNotification(
                appContext,
                appContext.getString(R.string.notif_connected, label),
                connected = true
            )
            Report.log("وصل شد از راه تماس بله")
            startBaleWatchdog(link)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Report.logError("اتصال بله", e)
            failBale(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
        }
    }

    /**
     * به تماس می‌پیوندد و ثابت می‌کند ترافیک واقعاً از آن رد می‌شود.
     *
     * @return false اگر نشد؛ حالت شکست و دلیلش از قبل ثبت شده است
     */
    private suspend fun joinBaleCall(link: String): Boolean {
        _state.value = State.Scanning(1, 1, appContext.getString(R.string.transport_bale))
        try {
            BaleTransport.start(link)
        } catch (e: Throwable) {
            Report.logError("پیوستن به تماس بله", e)
            failBale(e.message ?: "لینک تماس پذیرفته نشد")
            return false
        }

        val problem = BaleTransport.awaitConnected()
        if (problem != null) {
            Report.log("تماس بله برقرار نشد: " + problem)
            failBale(
                "به تماس بله وصل نشد: " + problem +
                    ". مطمئن شوید Creator همین الان روشن است و لینک تازه است."
            )
            return false
        }
        Report.log("تونل از دل تماس بله برقرار شد")

        // همان سنجه مسیر Xray: یک درخواست HTTP با پاسخ دانسته، و یک
        // پرس‌وجوی DNS، هر دو از دل تماس تا Creator و برگشت
        if (!trafficFlowsVia(BaleTransport.SOCKS_PORT)) {
            failBale(
                "تماس برقرار شد ولی ترافیک از آن رد نشد. Creator را بررسی کنید؛ " +
                    "ممکن است خودش اینترنت آزاد نداشته باشد."
            )
            return false
        }
        return true
    }

    private fun failBale(message: String) {
        BaleTransport.stop()
        AzadVpnService.stop(appContext)
        _state.value = State.Failed(message)
    }

    /**
     * نگهبان تماس بله. اگر تماس قطع شد، یعنی Creator رفت یا بله تماس را
     * بست، دوباره به همان لینک می‌پیوندد. رابط VPN سر جایش می‌ماند؛ پل همان
     * پورت را می‌شناسد و به محض بازگشت جوینر ترافیک دوباره جریان می‌گیرد.
     */
    private fun startBaleWatchdog(link: String) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var failures = 0
            while (isActive) {
                delay(WATCH_INTERVAL_MS)

                if (AzadVpnService.state.value is AzadVpnService.State.Revoked) {
                    Report.log("VPN از بیرون گرفته شد، نگهبان متوقف شد")
                    BaleTransport.stop()
                    _state.value = State.Disconnected
                    return@launch
                }

                val healthy = BaleTransport.status.value == BaleTransport.Status.Connected &&
                    ProxyProbe.trafficFlows(BaleTransport.SOCKS_PORT)
                if (healthy) {
                    failures = 0
                    continue
                }
                if (!hasUnderlyingNetwork()) {
                    Report.log("نگهبان: گوشی به شبکه وصل نیست، صبر می‌کنیم")
                    continue
                }

                failures++
                Report.log("نگهبان: تماس بله جواب نمی‌دهد، پیوستن دوباره (" + failures + ")")
                AzadVpnService.updateNotification(
                    appContext,
                    appContext.getString(R.string.notif_switching),
                    connected = true
                )
                BaleTransport.stop()
                if (rejoinBale(link)) {
                    failures = 0
                    val label = appContext.getString(R.string.transport_bale)
                    _state.value = State.Connected(label)
                    AzadVpnService.updateNotification(
                        appContext,
                        appContext.getString(R.string.notif_connected, label),
                        connected = true
                    )
                    continue
                }
                if (failures >= BALE_MAX_REJOINS) {
                    failBale("تماس بله قطع شد و پیوستن دوباره ممکن نشد. شاید Creator خاموش شده یا لینک عوض شده.")
                    return@launch
                }
            }
        }
    }

    private suspend fun rejoinBale(link: String): Boolean {
        return try {
            BaleTransport.start(link)
            BaleTransport.awaitConnected() == null && trafficFlowsVia(BaleTransport.SOCKS_PORT)
        } catch (e: Throwable) {
            Report.logError("پیوستن دوباره به تماس بله", e)
            false
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

            // سرویس از همین حالا پیش‌زمینه می‌شود، نه از لحظه وصل شدن
            AzadVpnService.prepare(appContext)

            // فهرست ایران موازی با جست‌وجوی سرورها تازه می‌شود. هفته‌ای یک
            // بار شبکه می‌زند و بقیه وقت‌ها فوراً برمی‌گردد.
            val listsReady = scope.async { refreshIranListIfNeeded() }

            val ranked = freshPool()
            if (ranked == null) {
                failXray("هیچ فهرست سروری دریافت نشد. اینترنت را بررسی کنید و دوباره بزنید.")
                return
            }
            if (ranked.isEmpty()) {
                failXray("هیچ‌کدام از سرورهای فهرست از این شبکه در دسترس نبودند.")
                return
            }
            pool = ranked
            listsReady.await()

            // غربال TCP فقط می‌گوید پورت باز است. خیلی از این سرورها
            // پورتشان باز است و دست‌دادن رمزنگاری‌شان شکست می‌خورد، یا کلید
            // و شناسه‌شان منقضی شده. پس اول چند ده سرور هم‌زمان با درخواست
            // واقعی سنجیده می‌شوند، و فقط آنهایی که جواب دادند به امتحان
            // کامل می‌رسند.
            val passed = screen(ranked)
            if (passed.isEmpty()) {
                failXray(
                    "هیچ‌کدام از سرورهای امتحان‌شده از این شبکه ترافیک رد نکردند. " +
                        "چند دقیقه بعد دوباره بزنید؛ فهرست‌ها مرتب عوض می‌شوند."
                )
                return
            }

            if (tryCandidates(passed)) {
                startWatchdog()
                return
            }

            // اگر ساخت رابط VPN خودش شکست خورده، پیام دقیق‌ترش از قبل ثبت شده
            if (_state.value !is State.Failed) {
                failXray(
                    "سرورهایی که جواب دادند در امتحان کامل، همراه با DNS، شکست خوردند. دوباره بزنید."
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Report.logError("اتصال Xray", e)
            failXray(e.javaClass.simpleName + ": " + (e.message ?: "بدون پیام"))
        }
    }

    private fun failXray(message: String) {
        recentlyTried.clear()
        store.serversInFlight = emptyList()
        AzadVpnService.stop(appContext)
        _state.value = State.Failed(message)
    }

    /**
     * فهرست‌ها را می‌گیرد و غربال می‌کند. null یعنی هیچ فهرستی نیامد،
     * فهرست خالی یعنی آمد ولی هیچ سروری زنده نبود.
     */
    private suspend fun freshPool(reportProgress: Boolean = true): List<ServerTester.Result>? {
        val cached = store.workingEndpoint
        val servers = ConfigSources.fetchAll(store.fragmentTls, store.strictIranDns)
        if (servers.isEmpty()) return null

        // اگر سروری قبلاً کار کرده بود، اول همان امتحان می‌شود
        val ordered = if (cached != null) {
            servers.sortedByDescending { it.key == cached }
        } else {
            servers
        }

        return ServerTester.rank(ordered, limit = SIFT_ALIVE) { tested, total, alive ->
            if (reportProgress) _state.value = State.Scanning(tested, total, "زنده: " + alive)
        }
    }

    /** نامزدها را به ترتیب تا آخر امتحان می‌کند. اولین سالم نگه داشته می‌شود. */
    /** سروری که آزمون هم‌زمان را رد کرده، با همان حالتی که در آن جواب داد. */
    private data class Passed(
        val result: ServerTester.Result,
        val fragment: Boolean,
        val latencyMs: Long
    )

    /**
     * آزمون هم‌زمان: چند ده سرور در یک هسته، هر کدام روی پورت خودش، و یک
     * درخواست واقعی به همه با هم.
     *
     * هر سرور TLS دو بار امتحان می‌شود، یک بار با تکه‌تکه کردن دست‌دادن و
     * یک بار بی آن. معلوم نیست تکه‌تکه کردن روی هر شبکه و هر CDN کمک کند؛
     * بعضی لبه‌ها دست‌دادن تکه‌تکه را نمی‌پذیرند. به جای حدس زدن، هر دو
     * سنجیده می‌شود و همانی که جواب داد نگه داشته می‌شود.
     */
    private suspend fun screen(candidates: List<ServerTester.Result>): List<Passed> {
        val quarantined = store.quarantine
        var malformed = 0
        val picked = candidates
            .asSequence()
            .filter { it.link.identity !in quarantined }
            // یک سرور با ده آی‌پی ورودی مختلف هنوز یک سرور است
            .distinctBy { it.link.identity }
            // لینک خرابی که هسته نمی‌پذیرد، اگر وارد کانفیگ مشترک شود کل
            // آزمون را از کار می‌اندازد. هر سرور جدا سنجیده می‌شود؛ زیر یک
            // میلی‌ثانیه طول می‌کشد.
            .filter { candidate ->
                val ok = runCatching {
                    Azadcore.checkXray(
                        XrayConfig.build(candidate.link, AzadVpnService.DEFAULT_SOCKS_PORT, candidate.ip)
                    )
                }.isSuccess
                if (!ok) malformed++
                ok
            }
            .take(SCREEN_SIZE)
            .toList()
        val skipped = candidates.count { it.link.identity in quarantined }
        if (skipped > 0) Report.log(skipped.toString() + " نامزد قرنطینه‌شده کنار گذاشته شد")
        if (malformed > 0) Report.log(malformed.toString() + " لینک خراب که هسته نپذیرفت کنار گذاشته شد")
        if (picked.isEmpty()) return emptyList()

        val slots = ArrayList<XrayConfig.ScreenSlot>()
        val owners = ArrayList<ServerTester.Result>()
        var port = SCREEN_BASE_PORT
        for (candidate in picked) {
            val variants = if (store.fragmentTls && XrayConfig.canFragment(candidate.link)) {
                listOf(true, false)
            } else {
                listOf(false)
            }
            for (fragment in variants) {
                slots.add(XrayConfig.ScreenSlot(candidate.link, candidate.ip, fragment, port++))
                owners.add(candidate)
            }
        }

        _state.value = State.Scanning(0, picked.size, "آزمون هم‌زمان " + picked.size + " سرور")
        Report.log("آزمون هم‌زمان: " + picked.size + " سرور متفاوت در " + slots.size + " حالت")

        CoreCrash.install(appContext)
        runCatching { Azadcore.stopXray() }
        try {
            Azadcore.startXray(XrayConfig.buildScreening(slots))
        } catch (e: Throwable) {
            Report.logError("آزمون هم‌زمان", e)
            return emptyList()
        }

        val latencies = try {
            coroutineScope {
                slots.map { slot ->
                    async(Dispatchers.IO) { ProxyProbe.httpLatency(slot.port, quiet = true) }
                }.awaitAll()
            }
        } finally {
            runCatching { Azadcore.stopXray() }
        }

        // از هر سرور، حالتی که سریع‌تر جواب داد
        val best = LinkedHashMap<String, Passed>()
        slots.forEachIndexed { i, slot ->
            val ms = latencies[i] ?: return@forEachIndexed
            val owner = owners[i]
            val previous = best[owner.link.identity]
            if (previous == null || ms < previous.latencyMs) {
                best[owner.link.identity] = Passed(owner, slot.fragment, ms)
            }
        }
        val passed = best.values.sortedBy { it.latencyMs }

        val withFragment = slots.indices.count { latencies[it] != null && slots[it].fragment }
        val plain = slots.indices.count { latencies[it] != null && !slots[it].fragment }
        Report.log(
            "آزمون هم‌زمان: " + passed.size + " از " + picked.size + " سرور جواب دادند" +
                " (با تکه‌تکه " + withFragment + "، بی‌تکه " + plain + ")"
        )
        return passed
    }

    private suspend fun tryCandidates(passed: List<Passed>): Boolean {
        val attempts = passed.take(fullAttempts)
        Report.log("امتحان کامل روی " + attempts.size + " سرور")

        attempts.forEachIndexed { index, entry ->
            val candidate = entry.result
            _state.value = State.Scanning(index + 1, attempts.size, candidate.link.label)
            Report.log(
                "نامزد " + (index + 1) + ": " + candidate.link.label +
                    " [" + candidate.ip + "] " + entry.latencyMs + " میلی‌ثانیه" +
                    (if (entry.fragment) "، تکه‌تکه" else "")
            )

            if (tryXrayServer(candidate, entry.fragment)) {
                // سرور ثابت کرد ترافیک رد می‌کند؛ حالا و فقط حالا رابط VPN
                // ساخته می‌شود. اگر از قبل برقرار است (جابه‌جایی سرور)، پل
                // همان پورت را می‌شناسد و دست زدن به آن لازم نیست.
                if (!AzadVpnService.isEstablished() && !bringUpInterface(candidate.link.label)) {
                    runCatching { Azadcore.stopXray() }
                    return false
                }

                // وصل شدیم؛ دیگر هیچ سروری «وسط امتحان» نیست
                recentlyTried.clear()
                store.serversInFlight = emptyList()
                current = candidate
                store.workingEndpoint = candidate.link.key
                _state.value = State.Connected(candidate.link.label)
                AzadVpnService.updateNotification(
                    appContext,
                    appContext.getString(R.string.notif_connected, candidate.link.label),
                    connected = true
                )
                Report.log("وصل شد به " + candidate.link.label)
                return true
            }

            runCatching { Azadcore.stopXray() }
        }
        return false
    }

    /** رابط VPN را می‌سازد و صبر می‌کند تا واقعاً برقرار شود. */
    private suspend fun bringUpInterface(label: String): Boolean {
        AzadVpnService.establish(appContext, AzadVpnService.DEFAULT_SOCKS_PORT, label)
        return when (val outcome = awaitTunnel()) {
            TunnelOutcome.Established -> true
            is TunnelOutcome.Failed -> {
                failXray(outcome.message)
                false
            }
            TunnelOutcome.TimedOut -> {
                failXray("رابط VPN در زمان مقرر ساخته نشد.")
                false
            }
        }
    }

    /**
     * یک سرور را تا آخر امتحان می‌کند: تونل بالا بیاید و ترافیک واقعی از آن
     * عبور کند. اگر هر کدام نشد، false و دلیلش در گزارش.
     */
    private suspend fun tryXrayServer(candidate: ServerTester.Result, fragment: Boolean): Boolean {
        // پیش از بالا آوردن هسته ثبت می‌شود، چون اگر این سرور هسته را
        // بکشد فرصت دیگری برای ثبت نیست
        recentlyTried.remove(candidate.link.identity)
        recentlyTried.addFirst(candidate.link.identity)
        while (recentlyTried.size > 2) recentlyTried.removeLast()
        store.serversInFlight = recentlyTried.toList()

        val config = XrayConfig.build(
            candidate.link,
            AzadVpnService.DEFAULT_SOCKS_PORT,
            candidate.ip,
            if (store.domesticDirect) IranList.load(appContext) else null,
            fragment = fragment
        )

        // امتحان فقط با خود هسته، بدون رابط VPN. برنامه از VPN کنار گذاشته
        // شده و سنجه مستقیم به پروکسی محلی وصل می‌شود، پس رابط VPN در این
        // امتحان هیچ نقشی ندارد. نبودنش یعنی بقیه برنامه‌های گوشی در طول
        // جست‌وجو اینترنت عادی‌شان را دارند، نه یک تونل مرده.
        CoreCrash.install(appContext)
        runCatching { Azadcore.stopXray() }
        try {
            Azadcore.startXray(config)
        } catch (e: Throwable) {
            Report.logError("بالا آوردن هسته", e)
            return false
        }
        Report.log("هسته Xray بالا آمد، نسخه " + Azadcore.xrayVersion() + "، طول کانفیگ " + config.length)

        return trafficFlows()
    }

    private fun refreshIranListIfNeeded() {
        if (!store.domesticDirect) return
        runCatching {
            IranList.refreshIfStale(appContext, store.fragmentTls, store.strictIranDns)
        }.onFailure { Report.logError("به‌روزرسانی فهرست ایران", it) }
        val lists = IranList.load(appContext)
        Report.log(
            "مسیر مستقیم ایران: " + lists.cidrs.size + " رنج و " +
                lists.domains.size + " نام"
        )
    }

    /**
     * نگهبان اتصال.
     *
     * سرورهای رایگان بی‌خبر می‌میرند: صاحبش خاموشش می‌کند، کلیدش عوض
     * می‌شود، یا اپراتور آی‌پی‌اش را می‌بندد. بدون نگهبان، تونل «وصل»
     * می‌ماند و اینترنت نمی‌آید تا وقتی کاربر خودش بفهمد و دوباره بزند.
     *
     * هر نیم دقیقه یک درخواست واقعی از تونل رد می‌شود. دو شکست پشت سر هم
     * یعنی سرور رفته، و بی‌صدا به سرور بعدی از فهرست ذخیره جابه‌جا می‌شویم.
     * فهرست هم هر نیم ساعت در پس‌زمینه تازه می‌شود تا وقت جابه‌جایی،
     * نامزدها کهنه نباشند.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var misses = 0
            var lastPoolRefresh = System.currentTimeMillis()

            while (isActive) {
                delay(WATCH_INTERVAL_MS)

                when (AzadVpnService.state.value) {
                    is AzadVpnService.State.Revoked -> {
                        // کاربر از تنظیمات اندروید قطع کرد یا VPN دیگری جایش را گرفت.
                        // این تصمیم کاربر است و نباید با وصل کردن دوباره خنثی‌اش کرد.
                        Report.log("VPN از بیرون گرفته شد، نگهبان متوقف شد")
                        _state.value = State.Disconnected
                        return@launch
                    }

                    is AzadVpnService.State.Connected -> {
                        if (ProxyProbe.trafficFlows(AzadVpnService.DEFAULT_SOCKS_PORT)) {
                            misses = 0
                        } else {
                            misses++
                            Report.log("نگهبان: سنجش ناموفق " + misses + " از " + MISSES_BEFORE_SWITCH)
                        }
                    }

                    else -> misses = MISSES_BEFORE_SWITCH
                }

                if (misses >= MISSES_BEFORE_SWITCH) {
                    if (!hasUnderlyingNetwork()) {
                        // خود گوشی اینترنت ندارد؛ عوض کردن سرور فایده‌ای ندارد و
                        // فقط فهرست را بی‌جهت می‌سوزاند. صبر می‌کنیم تا شبکه برگردد.
                        Report.log("نگهبان: گوشی به شبکه وصل نیست، صبر می‌کنیم")
                        continue
                    }
                    misses = 0
                    if (!switchServer()) {
                        if (_state.value !is State.Failed) {
                            failXray("ارتباط با سرور قطع شد و سرور جایگزینی پیدا نشد. دوباره بزنید.")
                        }
                        return@launch
                    }
                    lastPoolRefresh = System.currentTimeMillis()
                    continue
                }

                if (System.currentTimeMillis() - lastPoolRefresh > POOL_REFRESH_MS) {
                    lastPoolRefresh = System.currentTimeMillis()
                    refreshPoolQuietly()
                }
            }
        }
    }

    /** به سرور سالم بعدی جابه‌جا می‌شود. اگر ذخیره ته کشید، فهرست تازه می‌گیرد. */
    private suspend fun switchServer(): Boolean {
        val dead = current?.link?.key
        Report.log("سرور فعلی جواب نمی‌دهد، جابه‌جایی")
        AzadVpnService.updateNotification(
            appContext,
            appContext.getString(R.string.notif_switching),
            connected = true
        )

        var candidates = pool.filter { it.link.key != dead }
        if (candidates.size < 2) {
            Report.log("ذخیره سرورها ته کشید، گرفتن فهرست تازه")
            candidates = freshPool(reportProgress = false).orEmpty()
                .filter { it.link.key != dead }
            pool = candidates
        } else {
            pool = candidates
        }

        if (candidates.isEmpty()) return false
        val passed = screen(candidates)
        if (passed.isEmpty()) return false
        return tryCandidates(passed)
    }

    /** فهرست ذخیره را بدون دست زدن به تونل فعلی تازه می‌کند. */
    private suspend fun refreshPoolQuietly() {
        runCatching {
            val fresh = freshPool(reportProgress = false)
            if (!fresh.isNullOrEmpty()) {
                pool = fresh
                Report.log("فهرست ذخیره تازه شد: " + fresh.size + " سرور زنده")
            }
        }.onFailure { Report.logError("تازه کردن فهرست ذخیره", it) }
        // وضعیت را دست‌نخورده نگه می‌داریم؛ کاربر نباید «در حال جست‌وجو» ببیند
        current?.let { _state.value = State.Connected(it.link.label) }
        refreshIranListIfNeeded()
    }

    /**
     * آیا گوشی جدا از تونل ما شبکه‌ای دارد که اینترنت بدهد؟
     *
     * برنامه خودش از VPN کنار گذاشته شده، پس شبکه‌ای که به دردش می‌خورد
     * شبکه زیرین است نه خود VPN. اگر هیچ شبکه غیر VPN با اینترنت نباشد،
     * شکست سنجش تقصیر سرور نیست.
     */
    @Suppress("DEPRECATION")
    private fun hasUnderlyingNetwork(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrDefault(true)

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
                is AzadVpnService.State.Revoked -> return TunnelOutcome.Failed("اجازه VPN پس گرفته شد")
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
    private suspend fun trafficFlows(): Boolean = trafficFlowsVia(AzadVpnService.DEFAULT_SOCKS_PORT)

    private suspend fun trafficFlowsVia(socksPort: Int): Boolean {
        // دو تلاش و نه بیشتر. هر تلاش ناموفق چند ثانیه می‌گیرد و چند نامزد
        // پشت سر این صف ایستاده‌اند؛ سروری که دو بار جواب نداد را رها
        // می‌کنیم و وقت را روی نامزد بعدی می‌گذاریم.
        repeat(2) { attempt ->
            if (ProxyProbe.trafficFlows(socksPort)) {
                Report.log("ترافیک از سرور عبور کرد")
                return true
            }
            if (attempt < 1) delay(1_000)
        }
        Report.log("هسته بالا آمد ولی ترافیکی از سرور عبور نکرد")
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
        // اول نگهبان و اتصال نیمه‌کاره، وگرنه یکی‌شان بلافاصله دوباره وصل می‌کند
        watchdogJob?.cancel()
        connectJob?.cancel()
        watchdogJob = null
        connectJob = null
        current = null
        recentlyTried.clear()
        store.serversInFlight = emptyList()

        stopTunnel()
        BaleTransport.stop()
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
        /** هر چند وقت یک بار تونل سنجیده شود. */
        private const val WATCH_INTERVAL_MS = 30_000L

        /** چند بار پشت سر هم پیوستن دوباره به تماس بله امتحان شود. */
        private const val BALE_MAX_REJOINS = 3

        /** غربال TCP تا پیدا کردن این تعداد سرور زنده ادامه می‌دهد. */
        private const val SIFT_ALIVE = 60

        /** چند سرور متفاوت در آزمون هم‌زمان سنجیده شوند. */
        private const val SCREEN_SIZE = 24

        /** پورت‌های محلی آزمون هم‌زمان از اینجا شروع می‌شوند. */
        private const val SCREEN_BASE_PORT = 20810

        /** چند شکست پشت سر هم تا سرور مرده حساب شود. یکی ممکن است اتفاقی باشد. */
        private const val MISSES_BEFORE_SWITCH = 2

        /** فهرست ذخیره هر نیم ساعت در پس‌زمینه تازه می‌شود. */
        private const val POOL_REFRESH_MS = 30L * 60 * 1000

        @Volatile
        private var instance: VpnManager? = null

        fun get(context: Context): VpnManager =
            instance ?: synchronized(this) {
                instance ?: VpnManager(context).also { instance = it }
            }
    }
}
