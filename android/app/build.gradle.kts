plugins {
    id("com.android.application")
}

android {
    namespace = "com.linkbridge.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linkbridge.android"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates since 2026-08-31.
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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

    testImplementation("junit:junit:4.13.2")
}
