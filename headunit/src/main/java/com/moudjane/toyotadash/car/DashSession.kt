package com.moudjane.toyotadash.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One Android Auto session = one cockpit screen. */
class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CockpitScreen(carContext)
}
