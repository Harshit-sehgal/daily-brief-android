plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.room)
}

val releaseSigningVariables = listOf(
  "KEYSTORE_PATH",
  "STORE_PASSWORD",
  "KEY_ALIAS",
  "KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
  providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
}
val configuredReleaseSigningVariables = releaseSigningValues.filterValues { it != null }.keys
val releaseSigningConfigured = configuredReleaseSigningVariables.size == releaseSigningVariables.size

if (configuredReleaseSigningVariables.isNotEmpty() && !releaseSigningConfigured) {
  val missing = releaseSigningVariables - configuredReleaseSigningVariables
  throw GradleException(
    "Release signing is only partially configured. Set all of " +
      releaseSigningVariables.joinToString() + ". Missing: ${missing.joinToString()}",
  )
}

android {
  namespace = "com.example"
  compileSdk { version = release(37) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.dailybrief.jxhvqy"
    minSdk = 24
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (releaseSigningConfigured) {
      create("release") {
        storeFile = rootProject.file(releaseSigningValues.getValue("KEYSTORE_PATH")!!)
        storePassword = releaseSigningValues.getValue("STORE_PASSWORD")
        keyAlias = releaseSigningValues.getValue("KEY_ALIAS")
        keyPassword = releaseSigningValues.getValue("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (releaseSigningConfigured) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    // Required for BuildConfig.APPLICATION_ID in the notification receiver.
    // No credentials or other custom fields are generated.
    buildConfig = true
    compose = true
  }
}

room {
  schemaDirectory("$projectDir/schemas")
}

// The app only depends on libraries referenced by production or test source.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
}
