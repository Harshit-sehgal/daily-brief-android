// AGP 9 bundles an older Kotlin Gradle plugin and constrains KSP. Declaring the
// selected stable versions here is AGP's supported upgrade path for built-in Kotlin.
// Keep these coordinates aligned with gradle/libs.versions.toml.
buildscript {
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.room) apply false
}
