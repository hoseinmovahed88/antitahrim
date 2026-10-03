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
 *   ۲. نقاط اتصال را یکی یکی امتحان می‌کند تا یکی پیدا شود که واقعاً
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
        /** دست‌دادن انجام شد و ترافیک هم رد وبدل شد. */
        WORKING,

        /** دست‌دادن انجام شد ولی ترافیک عبور نکر دشد. یعنی نقطه زنده است. */
        HANDSHAKE_ONLY,

        /** هیچ پاسخی نیامد. یعنی بسته ها اصلاً به مقصد نرسیدند یا برنگشتند. */
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

    /** چند سرور Xray با تونل واقعی امتحان شود، نه فقط با 뚉��بال TCP. */
    private val fullAttempts = 6

    /** سرورهایی اخیراً امتحان‌شده در همین دور، تازه‌ترین اول. */
    private val recentlyTried = ArrayDeque<String>()

    init {
        quarantineAfterCrash()
    }

    /**
     * اگر فرایند وسط امتحان یک سرور مرده باشد، آن سرور مقصر است.
     *
     * بعضی کانفیگ‌های فهرست‌های عمومی هسته را به وحشت می‌اندازند، و وحشت
     * در goroutine هسته کل برنامه را می‌کشد، وحشت در goroutine هسته کل برنامه را می‌کشدؚ�� he{‌نيفتدل ی
     * کهمىم شود ًۑ��e�� hHd. 
U© لوک�Ȱ"