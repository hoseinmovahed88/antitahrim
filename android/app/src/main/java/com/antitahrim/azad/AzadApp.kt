package com.antitahrim.azad

import android.app.Application
import android.os.Build
import com.antitahrim.azad.core.Report

/**
 * گزارش را زود راه می‌اندازد و کرش‌ها را نگه می‌دارد.
 *
 * بدون این، اگر برنامه هنگام اتصال کرش کند کاربر فقط می‌بیند که برنامه بسته
 * شد و هیچ سرنخی نمی‌ماند. حالا دفعه بعد که باز شود، علت کرش را نشان می‌دهد.
 */
class AzadApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Report.init(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Report.recordCrash(error) }
            previous?.uncaughtException(thread, error)
        }

        Report.log(
            "برنامه باز شد — اندروید " + Build.VERSION.RELEASE +
                "، " + Build.MANUFACTURER + " " + Build.MODEL +
                "، " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "نامشخص")
        )
    }
}
