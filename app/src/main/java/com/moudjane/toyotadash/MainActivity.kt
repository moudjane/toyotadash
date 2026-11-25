package com.moudjane.toyotadash

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moudjane.toyotadash.ui.theme.DashTextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moudjane.toyotadash.data.obd2.ObdBridgeServer
import com.moudjane.toyotadash.ui.obd.ConnectScreen
import com.moudjane.toyotadash.ui.obd.ConnectionStatus
import com.moudjane.toyotadash.ui.obd.ObdDashboardScreen
import com.moudjane.toyotadash.ui.obd.ObdViewModel
import com.moudjane.toyotadash.ui.theme.DashBackground
import com.moudjane.toyotadash.ui.theme.ToyotaDashTheme

class MainActivity : ComponentActivity() {

    /** Debug builds only: TCP bridge to the ELM327 on 127.0.0.1:35000 (see ObdBridgeServer). */
    private var obdBridge: ObdBridgeServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            requestBridgePermissions()
            obdBridge = ObdBridgeServer(applicationContext).also { it.start(); it.startSettingsDump() }
        }

        setContent {
            ToyotaDashTheme {
                ProvideTextStyle(DashTextStyle) {
                    val obdVm: ObdViewModel = viewModel()
                    val obdState by obdVm.state.collectAsState()

                    // Debug builds: `adb shell am start -n …/.MainActivity --ez demo true`
                    // opens straight into the simulator (screenshots, design work).
                    LaunchedEffect(Unit) {
                        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                        if (debuggable && intent.getBooleanExtra("demo", false) && !obdState.isSimulated) {
                            obdVm.toggleSimulator()
                        }
                        if (debuggable && intent.getBooleanExtra("fakedongles", false)) obdVm.debugFakeDevices()
                    }

                    Box(Modifier.fillMaxSize().background(DashBackground)) {
                        // Single screen: the OBD2 dashboard, or the dongle picker while not connected.
                        if (obdState.connectionStatus == ConnectionStatus.CONNECTED) {
                            ObdDashboardScreen(state = obdState, vm = obdVm)
                        } else {
                            ConnectScreen(state = obdState, vm = obdVm)
                        }
                    }
                }
            }
        }
    }

    /** Immersive full screen: status and navigation bars hidden, a swipe shows them briefly. */
    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen() // Bars come back after dialogs (permissions) and app switches
    }

    /** BLE scan (bridge) needs location on Android 6-11, BLUETOOTH_SCAN/CONNECT on 12+. */
    private fun requestBridgePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onDestroy() {
        obdBridge?.stop()
        super.onDestroy()
    }
}
