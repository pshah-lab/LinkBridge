plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.linkbridge.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linkbridge.android"
        // Android 12+: the pairing key uses KeyProperties.PURPOSE_AGREE_KEY (API 31) and
        // received files go through MediaStore.Downloads (API 29).
        minSdk = 31
        // Google Play requires API 36 for new apps and updates since 2026-08-31.
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    val signingStoreFile = providers.gradleProperty("linkbridgeStoreFile").orNull
    val signingStorePassword = providers.gradleProperty("linkbridgeStorePassword").orNull
    val signingKeyAlias = providers.gradleProperty("linkbridgeKeyAlias").orNull
    val signingKeyPassword = providers.gradleProperty("linkbridgeKeyPassword").orNull
    val hasPrivateSigning = listOf(
        signingStoreFile,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword
    ).all { !it.isNullOrBlank() }

    if (hasPrivateSigning) {
        signingConfigs {
            create("privateRelease") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasPrivateSigning) signingConfig = signingConfigs.getByName("privateRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            // The BouncyCastle jars each ship the same license file; keep one copy.
            pickFirsts += "META-INF/LICENSE.md"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Compose, pinned to the newest line that still compiles against android-36: Compose UI
    // 1.12+, material3 1.5.0-alpha19+, lifecycle 2.11+ and Haze 1.7.3 require compileSdk 37.
    // BOM 2026.06.01 = Compose UI 1.11.4. Its material3 (1.4.x) keeps MotionScheme.expressive()
    // internal, so material3 is pinned to 1.5.0-alpha18 (public Expressive APIs, compileSdk 35+).
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    // Only for the XML window theme (Theme.Material3.DayNight) shown before Compose draws.
    implementation("com.google.android.material:material:1.14.0")
    // TLS-PSK between paired devices (Android's built-in TLS has no usable PSK support).
    implementation("org.bouncycastle:bctls-jdk18on:1.86.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (the android.jar copy is a stub that throws).
    testImplementation("org.json:json:20240303")
}
