plugins {
    // AGP 8.10 / Kotlin 2.0.21 / compileSdk 36 (current, working toolchain).
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.x ships the Compose compiler as a Gradle plugin (replaces kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
