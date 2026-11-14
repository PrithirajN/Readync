// Versions match what Android Studio already cached on the development PC
// (AGP 8.12.0, Gradle 8.13). Kotlin is 2.4: the on-device AI library (LiteRT-LM) is built with it,
// and older Kotlin compilers can't read it.
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
