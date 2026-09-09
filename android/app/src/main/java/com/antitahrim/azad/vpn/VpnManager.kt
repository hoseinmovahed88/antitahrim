package com.antitahrim.azad.vpn

import android.content.Context
import com.antitahrim.azad.net.IranDns
import com.antitahrim.azad.warp.Endpoints
import com.antitahrim.azad.warp.Store
import com.antitahrim.azad.warp.WarpAccount
import com.antitahrim.azad.warp.WarpRegistrar
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
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
        data class Scanning(val tried: Int, val total: Int, val endpoint: String) : State
        data class Connected(val endpoint: String) : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val store = Store(appContext)
    private val backend: Backend by lazy { GoBackend(appContext) }
    private val tunnel = AzadTunnel { onBackendState(it) }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** چند نقطه اتصال قبل از تسلیم شدن امتحان شود. */
    private val scanBudget = 14

    private fun note(message: String) {
        _log.value = (_log.value + message).takeLast(60)
    }

    private fun onBackendState(newState: Tunnel.State) {
        if (newState == Tunnel.State.DOWN && _state.value is State.Connected) {
            _state.value = State.Disconnected
        }
    }

    fun isConnected(): Boolean =
        runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            val account = ensureAccount()

            val cached = store.workingEndpoint
            if (cached != null) {
                note("امتحان آخرین نقطه موفق: $cached")
                if (tryEndpoint(account, cached)) {
                    finishConnected(account, cached)
                    return@withContext
                }
                note("آن نقطه دیگر جواب نمی‌دهد، جست‌وجوی دوباره")
                store.workingEndpoint = null
            }

            val candidates = Endpoints.candidates(scanBudget)
            candidates.forEachIndexed { index, endpoint ->
                _state.value = State.Scanning(index + 1, candidates.size, endpoint)
                note("امتحان $endpoint")
                if (tryEndpoint(account, endpoint)) {
                    store.workingEndpoint = endpoint
                    finishConnected(account, endpoint)
                    return@withContext
                }
            }

            stopTunnel()
            _state.value = State.Failed("هیچ نقطه اتصالی جواب نداد. اینترنت را بررسی کنید و دوباره بزنید.")
        } catch (e: Exception) {
            stopTunnel()
            _state.value = State.Failed(e.message ?: "خطای ناشناخته")
            note("خطا: ${e.message}")
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        stopTunnel()
        _state.value = State.Disconnected
        note("قطع شد")
    }

    /** حساب موجود را برمی‌دارد یا یکی تازه می‌سازد. */
    private fun ensureAccount(): WarpAccount {
        store.account?.let { return it }

        _state.value = State.Registering
        note("ساخت حساب رایگان روی همین دستگاه")
        val registrar = WarpRegistrar(
            fragmentTls = store.fragmentTls,
            strictIranDns = store.strictIranDns
        )
        val account = registrar.register()
        store.account = account
        note("حساب ساخته شد")
        return account
    }

    /**
     * تونل را با این نقطه بالا می‌آورد و با یک پرس‌وجوی DNS از داخل تونل
     * ثابت می‌کند که واقعاً کار می‌کند. صرف بالا آمدن تونل معنایی ندارد؛
     * WireGuard وقتی مقصد جواب نمی‌دهد هم «بالا» به نظر می‌رسد.
     */
    private suspend fun tryEndpoint(account: WarpAccount, endpoint: String): Boolean {
        val config = runCatching {
            ConfigBuilder.build(account, endpoint, domesticDirect = false)
        }.getOrElse {
            note("کانفیگ ساخته نشد: ${it.message}")
            return false
        }

        runCatching { backend.setState(tunnel, Tunnel.State.UP, config) }.onFailure {
            note("تونل بالا نیامد: ${it.message}")
            return false
        }

        repeat(3) {
            delay(700)
            if (IranDns.probeThroughTunnel()) return true
        }
        stopTunnel()
        return false
    }

    /**
     * وقتی نقطه‌ای جواب داد، اگر کاربر «ترافیک داخلی مستقیم» را خواسته باشد
     * تونل با مسیرهای کامل دوباره ساخته می‌شود. جست‌وجو عمداً با مسیر ساده
     * انجام می‌شود چون فهرست بلند مسیرها هر بار بالا آمدن را کند می‌کند.
     */
    private fun finishConnected(account: WarpAccount, endpoint: String) {
        if (store.domesticDirect) {
            note("اعمال مسیر مستقیم برای سایت‌های ایرانی")
            val full = runCatching {
                ConfigBuilder.build(account, endpoint, domesticDirect = true)
            }.getOrNull()
            if (full != null) {
                runCatching { backend.setState(tunnel, Tunnel.State.UP, full) }.onFailure {
                    note("مسیر مستقیم اعمال نشد، با مسیر ساده ادامه می‌دهیم")
                }
            }
        }
        _state.value = State.Connected(endpoint)
        note("وصل شد به $endpoint")
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
