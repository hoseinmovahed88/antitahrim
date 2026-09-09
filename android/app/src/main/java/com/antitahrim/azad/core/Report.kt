package com.antitahrim.azad.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * گزارش تشخیصی.
 *
 * وقتی برنامه روی گوشی کسی وصل نمی‌شود، جمله «وصل نمی‌شود» هیچ‌چیز به ما
 * نمی‌گوید. این کلاس هر مرحله را با زمان و علت دقیق ثبت می‌کند و روی دیسک
 * نگه می‌دارد، تا حتی اگر برنامه بسته یا کرش شود گزارش باقی بماند.
 *
 * هیچ‌چیز از این گزارش جایی فرستاده نمی‌شود. فقط روی همین گوشی می‌ماند و
 * کاربر خودش تصمیم می‌گیرد کپی‌اش کند یا نه.
 */
object Report {

    private const val PREFS = "azad_report"
    private const val KEY_LINES = "lines"
    private const val KEY_CRASH = "crash"
    private const val MAX_LINES = 400

    private var prefs: SharedPreferences? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        _lines.value = store.getString(KEY_LINES, "")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    /** یک خط به گزارش اضافه می‌کند. */
    fun log(message: String) {
        val line = stamp.format(Date()) + "  " + message
        val updated = (_lines.value + line).takeLast(MAX_LINES)
        _lines.value = updated
        prefs?.edit()?.putString(KEY_LINES, updated.joinToString("\n"))?.apply()
    }

    /**
     * خطا را همراه نوعش ثبت می‌کند.
     *
     * نوع خطا مهم است. خیلی از استثناها پیام خالی دارند و بدون نام کلاس،
     * گزارش فقط می‌گوید «خطا» که هیچ کمکی نمی‌کند. علت زیرین هم ثبت می‌شود،
     * چون معمولاً همان است که واقعاً توضیح می‌دهد چه شد.
     */
    fun logError(stage: String, error: Throwable) {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: "بدون پیام"
        log(stage + " ناموفق — " + error.javaClass.simpleName + ": " + detail)
        error.cause?.let {
            val causeDetail = it.message?.takeIf { m -> m.isNotBlank() } ?: "بدون پیام"
            log("    علت زیرین: " + it.javaClass.simpleName + ": " + causeDetail)
        }
    }

    fun clear() {
        _lines.value = emptyList()
        prefs?.edit()?.remove(KEY_LINES)?.remove(KEY_CRASH)?.apply()
    }

    /** آخرین کرش ثبت‌شده، اگر وجود داشته باشد. */
    fun lastCrash(): String? = prefs?.getString(KEY_CRASH, null)

    fun clearCrash() {
        prefs?.edit()?.remove(KEY_CRASH)?.apply()
    }

    fun recordCrash(error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        // commit و نه apply، چون فرایند بلافاصله بعد از این می‌میرد
        prefs?.edit()?.putString(KEY_CRASH, writer.toString().take(4000))?.commit()
    }

    /** کل گزارش به صورت یک متن، برای کپی کردن. */
    fun asText(header: String): String = buildString {
        appendLine("گزارش آزاد")
        appendLine(header)
        appendLine("--------")
        lastCrash()?.let {
            appendLine("آخرین کرش:")
            appendLine(it)
            appendLine("--------")
        }
        _lines.value.forEach { appendLine(it) }
    }
}
