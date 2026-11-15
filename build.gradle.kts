// Top-level build file. All versions live in gradle/libs.versions.toml
// and were verified against Maven Central / Google Maven on 2026-11-15
// (see BUILD_NOTES.md for the verification log).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// NOTE (AGP 9.0+ built-in Kotlin): org.jetbrains.kotlin.android must NOT
// be applied anywhere — AGP applies the Kotlin Gradle plugin itself and
// applying kotlin-android fails the build (AgpWithBuiltInKotlinAppliedCheck).
