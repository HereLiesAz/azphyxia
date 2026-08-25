// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Apply the new compose plugin
    alias(libs.plugins.kotlin.compose) apply false

    // Hilt (still manual as it is not in the plugin block of toml yet)
    // Pinned to 2.58 for now; 2.59+ requires re-verifying against AGP 9 (see roadmap notes).
    id("com.google.dagger.hilt.android") version "2.58" apply false
}
