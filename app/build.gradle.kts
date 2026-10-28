import java.util.Properties

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
        versionCode = 5
        versionName = "1.5"
    }

    buildFeatures {
        compose = true
        buildConfig = true      // 设置页「关于」要读 BuildConfig.VERSION_NAME
    }

    // ---- 发布签名（keystore.properties 存在时自动启用；不存在则 release 回落 debug 签名）----
    // 生成 keystore:
    //   keytool -genkeypair -v -keystore b50-release.jks -alias b50 -keyalg RSA \
    //           -keysize 4096 -validity 10000
    // 写 keystore.properties（不要提交到仓库，已在 .gitignore 中）：
    //   storeFile=/绝对路径/b50-release.jks
    //   storePassword=...
    //   keyAlias=b50
    //   keyPassword=...
    // 优先级：环境变量 > keystore.properties > debug 签名
    // 用环境变量是为了让签名脚本"解密 -> 注入到进程环境 -> 构建 -> 清空"，
    // 磁盘上永远不出现明文密码。
    val envKeystore  = System.getenv("B50_KEYSTORE")
    val envStorePass = System.getenv("B50_STORE_PASS")
    val envKeyAlias  = System.getenv("B50_ALIAS")
    val envKeyPass   = System.getenv("B50_KEY_PASS")
    val keystorePropsFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (envKeystore != null && envStorePass != null && envKeyPass != null) {
            create("release") {
                storeFile = file(envKeystore)
                storePassword = envStorePass
                keyAlias = envKeyAlias ?: "keysigned_by_rinkyu"
                keyPassword = envKeyPass
            }
        } else if (keystorePropsFile.exists()) {
            val p = Properties().apply {
                keystorePropsFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
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
            // 有 keystore.properties 就正式签名，否则回落 debug 签名（能装不能发）
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
