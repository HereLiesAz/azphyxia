// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Apply the new compose plugin
    alias(libs.plugins.kotlin.compose) apply false

    // Hilt (still manual as it is not in the plugin block of toml yet)
    // 2.59.2+ is required for the Hilt Gradle plugin to work with AGP 9.
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}
