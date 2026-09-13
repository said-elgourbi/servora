import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/*
 * Servora Android application module.
 *
 * Toolchain and dependency versions live in `gradle/libs.versions.toml`; the
 * application version lives in `gradle.properties` (`docs/versioning.md` §8).
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Machine-local overrides. `android/local.properties` is git-ignored, so a value
 * that differs per developer machine (such as a LAN address a physical device
 * must reach) stays out of source control (`dev.md` §5).
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/** Reads a required application version property, failing loudly when absent. */
fun requiredVersionProperty(name: String): String =
    providers.gradleProperty(name).orNull
        ?: error("$name is missing from android/gradle.properties (docs/versioning.md §8).")

val servoraVersionName: String = requiredVersionProperty("servora.versionName")

val servoraVersionCode: Int = requiredVersionProperty("servora.versionCode")
    .toIntOrNull()
    ?: error("servora.versionCode must be an integer (docs/versioning.md §8).")

/**
 * Base URL of the Servora API.
 *
 * No environment address is hard-coded in application logic (`dev.md` §5).
 * Resolution order: `-Pservora.api.baseUrl=…` (one-off/CI build), then
 * `local.properties`, then the Android emulator alias for the host machine's
 * loopback interface.
 */
val servoraApiBaseUrl: String =
    providers.gradleProperty("servora.api.baseUrl").orNull
        ?: localProperties.getProperty("servora.api.baseUrl")
        ?: "http://10.0.2.2:3000/"

android {
    namespace = "com.servora.android"
    // AndroidX/Compose 1.12.0 require compiling against API 37. `targetSdk` is untouched:
    // it is a runtime behaviour decision, not a toolchain one.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.servora.android"
        minSdk = 26
        targetSdk = 36
        versionCode = servoraVersionCode
        versionName = servoraVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Configuration reaches the code through BuildConfig, never through a literal.
        buildConfigField("String", "API_BASE_URL", "\"$servoraApiBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // Keeps a development build installable next to a production install.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Release hardening (R8 keep rules verified against a real build) is its
            // own task; shipping an unverified shrink configuration is worse than
            // an unshrunk one.
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Supplies the per-app locale application and the app-wide night mode behind the sign-in
    // appearance controls (`docs/decisions/007-android-appearance-controls.md`).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Back stack for the signed-in area, so system Back and the secondary-screen arrow both pop
    // (`docs/decisions/010-android-navigation.md`).
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
