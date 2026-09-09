plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.antitahrim.azad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.antitahrim.azad"
        // کتابخانه WireGuard حداقل اندروید ۷ می‌خواهد
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("fa", "en")
    }

    /**
     * هسته Go بزرگ است و یک بسته همه‌کاره حدود ۱۲۵ مگابایت می‌شود.
     * برای کسی که با اینترنت گران و کند دانلود می‌کند این یعنی عملاً
     * غیرقابل استفاده. با تفکیک بر اساس معماری، هر گوشی فقط سهم خودش را
     * می‌گیرد و حجم تقریباً نصف می‌شود.
     *
     * همین include نقش abiFilters را هم بازی می‌کند و فقط همان دو معماری
     * را می‌سازد که هسته برایشان کامپایل شده. گذاشتن هر دو با هم خطای
     * پیکربندی می‌دهد، چون AGP اجازه نمی‌دهد دو جا معماری‌ها را تعیین کنید.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // امضای دیباگ تا خروجی CI روی گوشی نصب شود.
            // برای انتشار واقعی کلید خودتان را جایگزین کنید.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wireguard.tunnel)

    // هسته Go (Xray + پل tun) که CI با gomobile می‌سازد و در libs می‌گذارد.
    // fileTree استفاده می‌شود تا نبودن فایل خطای پیکربندی ندهد؛ اگر نباشد،
    // کدی که به آن نیاز دارد در همان کامپایل شکست می‌خورد و علتش روشن است.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    testImplementation(libs.junit)
    // اندروید org.json را در تست‌های واحد فقط به صورت استاب می‌دهد که استثنا
    // می‌اندازد. بدون نسخه واقعی، هر تستی که JSON بخواند شکست می‌خورد.
    testImplementation(libs.json)
}
