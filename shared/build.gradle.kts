// Shared OBD2 logic (Kotlin Multiplatform).
//   commonMain  : ELM327 protocol, PID formulas, dashboard logic — pure Kotlin
//   androidMain : Android Bluetooth (BLE GATT + Classic SPP) transports
//   iosMain     : iOS Bluetooth (CoreBluetooth BLE) transport
//   jvm         : only used to run the commonTest suite quickly on the dev machine
// iOS builds into Shared.xcframework, used by the iPhone / CarPlay app in ios/.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

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

    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcf.add(this)
        }
    }

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
