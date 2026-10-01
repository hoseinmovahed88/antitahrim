package com.antitahrim.azad.core

import android.content.Context
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
            ?: return false

        val trace = text.substring(start).take(MAX_TRACE)
        Report.recordCrashText("کرش هسته Go:\n" + trace)
        Report.log("دفعه قبل هسته از کار افتاد: " + trace.lineSequence().first().take(160))
        return true
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
