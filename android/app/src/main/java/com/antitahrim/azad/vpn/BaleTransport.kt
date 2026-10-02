package com.antitahrim.azad.vpn

import azadcore.Azadcore
import azadcore.BaleListener
import com.antitahrim.azad.core.Report
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * تونل از دل تماس تصویری بله.
 *
 * چرا: فیلترینگ ایران از ۱۴۰۳ به مدل «فهرست سفید» رفته. اتصال به آی‌پی
 * خارجی برقرار می‌شود، ولی هر نام دامنه‌ای که در فهرست نباشد بعد از چند
 * بسته قطع می‌شود؛ همان «Read timed out» گزارش‌ها. تماس تصویری بله سرویس
 * داخلی و در فهرست سفید است. اینجا ترافیک داخل همان تماس جا داده می‌شود و
 * برای سامانه بازرسی شبیه یک تماس تصویری معمولی است.
 *
 * طرف دیگر تماس، یعنی Creator، باید بیرون از ایران اجرا شود و لینک تماس را
 * بدهد. خود جوینر از پروژه متن‌باز whitelist-bypass-iran آمده است.
 */
object BaleTransport {

    /** پورت SOCKS محلی که جوینر بعد از برقراری تونل باز می‌کند. */
    const val SOCKS_PORT = 10818

    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Connected : Status
        data object Lost : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * جوینر برای هر بسته‌ای که رد و بدل می‌شود هم خط گزارش می‌نویسد. همه را
     * نگه داشتن گزارش را در چند ثانیه پر می‌کند و خطوط مهم بیرون می‌افتند؛
     * پس فقط خطوط مراحل اتصال و خطاها، و حداکثر چند ده خط در هر اتصال.
     */
    private const val MAX_LOG_LINES = 60
    private val logged = AtomicInteger(0)
    private val IMPORTANT = listOf(
        "[config]", "[auth]", "[session]", "[obf]", "TUNNEL", "SOCKS5 on",
        "error", "Error", "fail", "lost", "closed", "ended"
    )

    private val listener = object : BaleListener {
        override fun onLog(msg: String) {
            if (IMPORTANT.none { msg.contains(it) }) return
            if (logged.incrementAndGet() > MAX_LOG_LINES) return
            Report.log("بله: " + msg.take(200))
        }

        override fun onStatus(status: String) {
            when {
                status == "CONNECTING" -> _status.value = Status.Connecting
                status == "TUNNEL_CONNECTED" -> _status.value = Status.Connected
                status == "TUNNEL_LOST" -> _status.value = Status.Lost
                status.startsWith("ERROR") ->
                    _status.value = Status.Failed(status.removePrefix("ERROR").removePrefix(":"))
            }
        }
    }

    fun start(link: String) {
        logged.set(0)
        _status.value = Status.Connecting
        Azadcore.startBale(link, SOCKS_PORT.toLong(), "vp8", listener)
    }

    fun stop() {
        runCatching { Azadcore.stopBale() }
        _status.value = Status.Idle
    }

    /**
     * صبر می‌کند تا تونل برقرار شود یا شکست بخورد.
     *
     * پیوستن به تماس و برقراری WebRTC روی شبکه ایران گاهی تا یک دقیقه طول
     * می‌کشد، پس مهلت سخاوتمندانه است.
     *
     * @return null اگر برقرار شد، وگرنه دلیل شکست
     */
    suspend fun awaitConnected(timeoutMs: Long = 90_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val s = _status.value) {
                Status.Connected -> return null
                is Status.Failed -> return s.message
                Status.Lost -> return "تماس قطع شد"
                else -> delay(300)
            }
        }
        return "در زمان مقرر به تماس وصل نشد"
    }
}
