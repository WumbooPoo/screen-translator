// Top-level build file. All versions live in gradle/libs.versions.toml
// and were verified against Maven Central / Google Maven on 2026-11-15
// (see BUILD_NOTES.md for the verification log).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
