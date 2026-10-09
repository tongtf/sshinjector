import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

base {
    archivesName.set("sshinjector")
}

android {
    namespace = "cn.srv0.sshinjector"
    compileSdk = 37

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        applicationId = "cn.srv0.sshinjector"
        minSdk = 34
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // 统一签名 (跨机器/CI 一致): 仓库根 keystore.properties + keystore 文件 (均 gitignored, 各环境放同一份)
    // → 其次 KEYSTORE_* 环境变量 → 都没有则回退本机 debug key 并告警 (防止误以为已统一)。
    val unifiedProps =
        rootProject.file("keystore.properties").takeIf { it.exists() }?.let { propFile ->
            Properties().apply { propFile.inputStream().use { load(it) } }
        }
    val unifiedStorePath = (unifiedProps?.getProperty("storeFile") ?: System.getenv("KEYSTORE_PATH"))?.takeIf { it.isNotBlank() }
    val unifiedStorePass = (unifiedProps?.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD"))?.takeIf { it.isNotBlank() }
    val unifiedKeyAlias = (unifiedProps?.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS"))?.takeIf { it.isNotBlank() }
    val unifiedKeyPass = (unifiedProps?.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD"))?.takeIf { it.isNotBlank() }
    val unifiedSigningReady = unifiedStorePath != null && unifiedStorePass != null && unifiedKeyAlias != null
    if (!unifiedSigningReady) {
        logger.warn("[signing] keystore.properties / KEYSTORE_* 未配置 — 将使用本机各自生成的 debug key, 跨机器签名不一致!")
    }

    signingConfigs {
        create("release") {
            if (unifiedSigningReady) {
                storeFile = rootProject.file(requireNotNull(unifiedStorePath))
                storePassword = requireNotNull(unifiedStorePass)
                keyAlias = requireNotNull(unifiedKeyAlias)
                keyPassword = unifiedKeyPass ?: requireNotNull(unifiedStorePass)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (unifiedSigningReady) "release" else "debug")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // 统一签名: .debug 包同样使用共享 key, 跨机器一致
            if (unifiedSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 按平台 (ABI) 拆分 APK, 减小安装包体积; x86 与 x86_64 都不打包 —— Android 14+ 真机
    // 只有 arm64/armeabi (x86_64 仅模拟器, 不走 Play/侧载分发)。
    // -PforBundle 时禁用: AGP 9 下 splits+shrinkResources 与 bundleRelease 冲突
    // (issuetracker 402800800 — buildReleasePreBundle 收到多份 per-ABI shrunk resources)。
    // AAB 在 Play 侧本就按 ABI 自动分发, 不需要 APK splits。
    splits {
        abi {
            isEnable = !project.hasProperty("forBundle")
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        viewBinding = false
        dataBinding = false
        buildConfig = true
    }

    packaging {
        resources {
            excludes +=
                listOf(
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/license.txt",
                    "META-INF/NOTICE",
                    "META-INF/NOTICE.txt",
                    "META-INF/notice.txt",
                    "META-INF/services/java.net.spi.InetAddressResolverProvider",
                )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // SSH Client - mwiede/jsch (维护活跃的 JSch 分支，支持 Ed25519)
    implementation("com.github.mwiede:jsch:2.28.7")

    // DNS 解析
    implementation("dnsjava:dnsjava:3.6.5")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-data")
}

configurations.all {
    resolutionStrategy {
        force("androidx.tracing:tracing:1.1.0")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("hilt.disableAggregatingTask", "true")
}

detekt {
    config.setFrom(file("../detekt.yml"))
}

ktlint {
    android.set(true)
}

tasks.withType<Test>().configureEach {
    testLogging {
        // CI 上打印每个测试的开始/结果, 定位偶发挂死发生在哪个用例
        events("started", "passed", "failed", "skipped")
    }
}
