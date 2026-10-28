plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.b50.roast"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.b50.roast"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.4"
    }

    buildFeatures {
        compose = true
        buildConfig = true      // 设置页「关于」要读 BuildConfig.VERSION_NAME
    }

    // 发布版混淆 + 资源裁剪：把 client_id / 内部常量藏进 R8 乱码
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module")
        // libpy_home.so 其实是 python3 二进制，不能被 strip 工具动
        jniLibs.keepDebugSymbols += setOf("**/libpy_home.so", "**/libpython3.14.so", "**/libc++_shared.so")
        // tar.gz 已压缩，别再压一遍
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
}
