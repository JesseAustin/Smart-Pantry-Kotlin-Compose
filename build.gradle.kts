// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    // Google Services plugin for Firebase support
    alias(libs.plugins.google.services) apply false

    // Serialization plugin
    alias(libs.plugins.kotlin.serialization) apply false
}