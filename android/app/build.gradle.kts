plugins {
    id("com.android.application")
}

android {
    namespace = "com.dshpet.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshpet.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.4"
    }

    /**
     * assets 来源：
     *  1) src/main/assets —— 只放调试占位页（web/ 未就绪时给工程/服务自测用）；
     *  2) ../../web       —— T1「web-packager」产出的真实 dsh-pet 移动版资源包（直接打进 APK，
     *                        不复制、不修改，保持 web/ 是唯一事实来源）。
     * 两处目录不允许出现同名文件（会 merge 冲突）：占位页放在 debug/ 子目录下，永不冲突。
     */
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../../web")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?) ?: "dshpet2026"
            keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?) ?: "dshpet"
            keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?) ?: "dshpet2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // debug 也用同一把 release 密钥：两种包可互相覆盖安装（SharedPreferences 保留）
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // WebView 的 <video> 走 Range 请求；assets 里的 webm/字体必须保持不压缩，
        // 这样 AssetFileDescriptor 能拿到真实长度，shouldInterceptRequest 才能回 206 Partial Content。
        noCompress += listOf("webm", "ttf", "png", "jpg", "jpeg", "webp", "mp4")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 故意不引入任何依赖：纯 framework API + 无 AndroidX（SPEC §3.1）
}
