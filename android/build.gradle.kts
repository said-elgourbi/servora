// Root build file.
//
// Plugins are declared here with `apply false` so the version resolution lives in
// `gradle/libs.versions.toml` and each module opts in explicitly (`dev.md` §4).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
