plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.srv0.sshinjector"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.srv0.sshinjector"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val storeFileEnv = System.getenv("KEYSTORE_PATH") ?: ""
            val storePasswordEnv = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAliasEnv = System.getenv("KEY_ALIAS") ?: ""
            val keyPasswordEnv = System.getenv("KEY_PASSWORD") ?: ""
            if (storeFileEnv.isNotBlank() && storePasswordEnv.isNotBlank() && keyAliasEnv.isNotBlank() && keyPasswordEnv.isNotBlank()) {
                this.storeFile = file(storeFileEnv)
                this.storePassword = storePasswordEnv
                this.keyAlias = keyAliasEnv
                this.keyPassword = keyPasswordEnv
            } else {
                // Use debug keystore for local builds
                this.storeFile = file("debug.keystore")
                this.storePassword = "android"
                this.keyAlias = "androiddebugkey"
                this.keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.RequiresOptIn"
        )
    }

    buildFeatures {
        compose = true
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/services/java.net.spi.InetAddressResolverProvider"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // SSH Client - mwiede/jsch (维护活跃的 JSch 分支，支持 Ed25519)
    implementation("com.github.mwiede:jsch:0.2.14")

    // DNS 解析
    implementation("dnsjava:dnsjava:3.6.5")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.00"))
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
}