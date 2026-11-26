package com.moudjane.toyotadash.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Android Auto projection. Declared in the manifest with the NAVIGATION
 * category so the app is allowed to draw its own full-screen surface (the cockpit) — the same
 * trick CarPlay uses with CPMapTemplate on iOS.
 */
class DashCarAppService : CarAppService() {

    // Personal / development build: accept any host. A published app would instead pin the
    // known Android Auto / Automotive hosts (HostValidator.Builder + signature allowlist).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}
