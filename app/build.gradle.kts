plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mepass.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mepass.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "2.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // 签名：CI 通过环境变量注入；本地兜底 debug keystore
    signingConfigs {
        create("release") {
            val storeFileEnv = System.getenv("MEPASS_RELEASE_STORE_FILE")
            val storePwEnv = System.getenv("MEPASS_RELEASE_STORE_PASSWORD")
            val keyAliasEnv = System.getenv("MEPASS_RELEASE_KEY_ALIAS") ?: "mepass_release"
            val keyPwEnv = System.getenv("MEPASS_RELEASE_KEY_PASSWORD")
            if (!storeFileEnv.isNullOrBlank() && !storePwEnv.isNullOrBlank()) {
                storeFile = file(storeFileEnv)
                storePassword = storePwEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPwEnv ?: storePwEnv
            } else {
                val debugKs = file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (!debugKs.exists()) {
                    debugKs.parentFile?.mkdirs()
                    project.exec {
                        commandLine(
                            "keytool", "-genkeypair",
                            "-alias", "androiddebugkey",
                            "-keyalg", "RSA", "-keysize", "2048",
                            "-validity", "10950",
                            "-dname", "CN=Android Debug,O=Android,C=US",
                            "-keystore", debugKs.absolutePath,
                            "-storepass", "android",
                            "-keypass", "android",
                            "-noprompt"
                        )
                    }
                }
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 移除非标准块，最大化定制 ROM 兼容性
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Core library desugaring（让 minSdk 24 可用 java.time.*）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // BouncyCastle（Argon2id 纯 Java 实现，无 native 依赖）
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
