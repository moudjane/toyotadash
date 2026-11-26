package com.moudjane.toyotadash.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * The single Android Auto screen: a [NavigationTemplate] whose map surface we take over to
 * draw the cockpit ([CockpitRenderer]). NavigationTemplate is the only template that hands the
 * app a drawable surface, so it is what makes a custom cockpit possible on Android Auto.
 */
class CockpitScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CockpitRenderer(carContext)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        // No dongle on the car side yet: play the demo drive so there is always something moving.
        if (DashHub.values.value == null) DashHub.startDemo()
        renderer.start()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        renderer.stop()
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("DÉMO")
                    .setOnClickListener { DashHub.startDemo() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
