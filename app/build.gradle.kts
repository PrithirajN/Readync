import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.prithirajn.trackersync"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.prithirajn.trackersync"
        minSdk = 26
        targetSdk = 35
        versionCode = 327
        versionName = "3.2.3"
        // Only the processors phones and e-readers use (64-bit and older 32-bit ARM). The on-device
        // AI, text recognition and barcode libraries are native code, one copy per processor type, so
        // leaving out x86 (emulators, a few old tablets) takes away about half the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Native libraries stored compressed in the APK (a smaller download); Android unpacks them on install.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // Each edition has its own signing key, the same for every build of it, on any PC or on GitHub
    // Actions. Android only installs an update over an app (keeping its data, including tracker
    // sign-ins) when both are signed with the same key; a different key forces an uninstall, which
    // erases them. So never change either key once the app is installed.
    //   Yours ("full"):        signing/signing.properties        (your original key)
    //   Shared ("lite"):       signing/signing-shared.properties (its own key)
    // The keys live in signing/: keep them, back them up, and keep this project private.
    fun key(name: String, file: String) = rootProject.file("signing/$file").takeIf { it.exists() }?.let { f ->
        val p = Properties().apply { f.inputStream().use { load(it) } }
        signingConfigs.create(name) {
            storeFile = rootProject.file("signing/" + p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    val fullKey = key("full", "signing.properties")
    val sharedKey = key("shared", "signing-shared.properties")

    // Two editions from the same code, both called Readync. "full" is yours. "lite" is the one to
    // share: its own app (its own ID, so it installs beside yours; a dog-eared icon; its own key)
    // and no Kindle, Libby or Audible: their sources are swapped for empty stand-ins (src/stubs),
    // so their code isn't in it at all.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            resValue("string", "app_name", "Readync")
            buildConfigField("boolean", "FULL", "true")
            signingConfig = fullKey ?: signingConfigs.getByName("debug")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            resValue("string", "app_name", "Readync")
            buildConfigField("boolean", "FULL", "false")
            signingConfig = sharedKey ?: signingConfigs.getByName("debug")
        }
    }

    // The parts that aren't in the public code live in src/private, which only the private repo
    // has: the Book photo studio, and the Kindle, Libby and Audible connections. Without it (the
    // public repo), the build uses the stand-ins in src/stubs, so it still builds and runs.
    // The shared edition never gets Kindle, Libby or Audible, even when building privately.
    val hasPrivate = file("src/private").exists()
    sourceSets {
        getByName("main") { java.srcDir(if (hasPrivate) "src/private/studio/java" else "src/stubs/studio/java") }
        getByName("full") { java.srcDir(if (hasPrivate) "src/private/connectors/java" else "src/stubs/connectors/java") }
        getByName("lite") { java.srcDir("src/stubs/connectors/java") }
    }

    buildTypes {
        // The edition's own key, not the build type's (the debug build type would otherwise use
        // Android's throwaway debug key).
        debug {
            signingConfig = null
        }
        release {
            isMinifyEnabled = false
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Web page view in dark mode (algorithmic darkening) on WebViews that support it.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Reads the page number off a photo of a page, on the phone (Latin script model bundled).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Runs a language model you add (Gemma, Qwen, Llama, Phi… as .litertlm) on the phone, for the AI helpers.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // Reads a book's ISBN barcode off a photo of the back cover, on the phone.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // A live barcode scanner (Google's, run by Play services): a framing box over the camera that
    // reads the ISBN the moment it's in view. Needs no camera permission in this app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
