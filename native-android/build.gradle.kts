plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin): no org.jetbrains.kotlin.android plugin.
    id("com.android.library") version "9.5.2" apply false
    id("com.android.application") version "9.5.2" apply false
    id("com.android.test") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
