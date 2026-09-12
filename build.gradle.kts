// Top-level build file: plugin versions are declared once here (see gradle/libs.versions.toml)
// and applied in the individual modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
