plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.google.protobuf") version "0.10.0" apply false
    // Must equal the Kotlin that AGP 9.2 bundles (./gradlew buildEnvironment).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
