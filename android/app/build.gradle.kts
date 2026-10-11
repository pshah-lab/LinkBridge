plugins {
    id("com.android.application")
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

    packaging {
        resources {
            // The BouncyCastle jars each ship the same license file; keep one copy.
            pickFirsts += "META-INF/LICENSE.md"
        }
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
    // TLS-PSK between paired devices (Android's built-in TLS has no usable PSK support).
    implementation("org.bouncycastle:bctls-jdk18on:1.86.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
}
