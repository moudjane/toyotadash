// Shared OBD2 logic (Kotlin Multiplatform).
//   commonMain  : ELM327 protocol, PID formulas, dashboard logic — pure Kotlin
//   androidMain : Android Bluetooth (BLE GATT + Classic SPP) transports
//   jvm         : only used to run the commonTest suite quickly on the dev machine
// An iOS target (CoreBluetooth transport in iosMain) comes with the iPhone / CarPlay app.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.moudjane.toyotadash.shared"
        compileSdk = 37
        minSdk = 24
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
