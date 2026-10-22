plugins {
    id("com.android.application") version "9.4.1" apply false
    // Must equal the Kotlin Gradle plugin AGP 9.4.1 bundles (2.2.10, see `./gradlew buildEnvironment`).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
