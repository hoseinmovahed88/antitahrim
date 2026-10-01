package com.antitahrim.azad.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import azadcore.Azadcore
import java.io.File

/**
 * رد کرش‌های هسته Go.
 *
 * وحشت (panic) یک goroutine در هسته کل فرایند را بی‌صدا می‌کشد: نه استثنای
 * جاوایی در کار است که گیرنده کرش ببیند، نه چیزی در گزارش. از دید کاربر
 * برنامه فقط «می‌پرد بیرون». تنها جایی که Go ردش را می‌نویسد خروجی خطای
 * استاندارد است، که روی اندروید به هیچ‌جا وصل نیست.
 *
 * پس آن خروجی به یک فایل برده می‌شود، و دفعه بعد که برنامه باز شد، اگر
 * ردی از وحشت در آن باشد به گزارش منتقل می‌شود.
 */
object CoreCrash {

    private const val FILE_NAME = "core-stderr.txt"
    private const val PREFS = "azad_core_crash"
    private const val KEY_LAST_EXIT = "last_exit_timestamp"
    private const val MAX_TRACE = 3500

    @Volatile
    private var installed = false

    /**
     * در شروع برنامه صدا زده می‌شود، پیش از آنکه هسته در این فرایند بار شود.
     * اگر دفعه قبل هسته کرش کرده بود، رد آن را به گزارش می‌برد.
     *
     * @return true اگر کرشی پیدا شد
     */
    fun collect(context: Context): Boolean {
        val abnormal = describeLastExit(context)

        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return false

        val text = runCatching { file.readText() }.getOrDefault("")
        // فایل پاک می‌شود تا همین کرش دفعه بعد دوباره گزارش نشود. هسته هنوز
        // در این فرایند بار نشده، پس کسی آن را باز نگه نداشته.
        runCatching { file.delete() }

        val start = listOf("panic:", "fatal error:", "SIGSEGV", "SIGABRT")
            .map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()

        if (start == null) {
            // ردی از وحشت نیست، ولی اگر فرایند غیرعادی مرده بود، آخرین
            // چیزهایی که هسته نوشته ممکن است علت را بگوید؛ مثلاً یک
            // log.Fatal که پیش از خروج فقط یک خط می‌نویسد.
            if (abnormal) {
                val tail = text.lineSequence()
                    .filter { it.isNotBlank() && !it.contains("deprecated") }
                    .toList()
                    .takeLast(6)
                tail.forEach { Report.log("    هسته: " + it.take(200)) }
            }
            return false
        }

        val trace = text.substring(start).take(MAX_TRACE)
        Report.recordCrashText("کرش هسته Go:\n" + trace)
        Report.log("دفعه قبل هسته از کار افتاد: " + trace.lineSequence().first().take(160))
        return true
    }

    /**
     * از خود اندروید می‌پرسد فرایند قبلی چطور تمام شد.
     *
     * وقتی برنامه بی‌صدا ناپدید می‌شود، سه علت کاملاً متفاوت از بیرون عین
     * هم دیده می‌شوند: کرش بومی، خروج خود فرایند (وحشت Go با کد ۲ خارج
     * می‌شود)، و کشته شدن به دست سیستم به خاطر کمبود حافظه یا محدودیت
     * پس‌زمینه شیائومی. اندروید از نسخه ۱۱ علت را نگه می‌دارد.
     *
     * @return true اگر پایان قبلی غیرعادی بود
     */
    private fun describeLastExit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return false
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull() ?: return false

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (last.timestamp <= prefs.getLong(KEY_LAST_EXIT, 0L)) return false
            prefs.edit().putLong(KEY_LAST_EXIT, last.timestamp).apply()

            val abnormal = last.reason !in NORMAL_EXITS
            if (abnormal) {
                Report.log(
                    "پایان فرایند قبلی: " + reasonName(last.reason) +
                        " · وضعیت " + last.status +
                        " · حافظه " + (last.pss / 1024) + "/" + (last.rss / 1024) + " مگابایت" +
                        " · اهمیت " + last.importance +
                        (last.description?.let { " · " + it.take(160) } ?: "")
                )
            }
            abnormal
        }.getOrDefault(false)
    }

    private val NORMAL_EXITS = setOf(
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "خروج خود فرایند (EXIT_SELF)"
        ApplicationExitInfo.REASON_SIGNALED -> "کشته شدن با سیگنال (SIGNALED)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "کمبود حافظه (LOW_MEMORY)"
        ApplicationExitInfo.REASON_CRASH -> "کرش جاوا (CRASH)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "کرش بومی (CRASH_NATIVE)"
        ApplicationExitInfo.REASON_ANR -> "هنگ کردن (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "شکست راه‌اندازی"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "مصرف بیش از حد منابع"
        ApplicationExitInfo.REASON_FREEZER -> "منجمد شدن به دست سیستم (FREEZER)"
        ApplicationExitInfo.REASON_OTHER -> "دلیل دیگر (OTHER)"
        else -> "ناشناخته (" + reason + ")"
    }

    /** خروجی خطای هسته را به فایل می‌برد. یک بار در عمر فرایند کافی است. */
    fun install(context: Context) {
        if (installed) return
        val path = File(context.filesDir, FILE_NAME).absolutePath
        runCatching { Azadcore.setCrashLog(path) }
            .onSuccess { installed = true }
            .onFailure { Report.logError("آماده کردن ثبت کرش هسته", it) }
    }
}
