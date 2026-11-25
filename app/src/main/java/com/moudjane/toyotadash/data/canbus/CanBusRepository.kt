package com.moudjane.toyotadash.data.canbus

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Reads the head unit's live vehicle data from Settings.System, where com.hcn.autocan
 * publishes it. Two read strategies:
 *   1. Bulk query of the Settings.System provider — returns every name/value row, which
 *      lets us auto-discover exactly which car_* keys this unit populates.
 *   2. Fallback: read the known catalog keys one by one (if the bulk query is blocked).
 *
 * Reading Settings.System needs no permission. Live updates come from a ContentObserver.
 */
class CanBusRepository(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** Every Settings.System entry, as name → raw string value. Empty if unreadable. */
    fun readAll(): Map<String, String> {
        // Strategy 1: bulk query (gives us keys we might not know about).
        val bulk = readBulk()
        if (bulk.isNotEmpty()) return bulk

        // Strategy 2: targeted reads of known keys.
        val out = LinkedHashMap<String, String>()
        val keys = buildList {
            add(CanBusKeys.SPEED); add(CanBusKeys.AVG_SPEED); add(CanBusKeys.SPEED_UNIT)
            add(CanBusKeys.GEAR); add(CanBusKeys.DRIVING_MODE); add(CanBusKeys.BATTERY_VOLUME)
            add(CanBusKeys.READY); add(CanBusKeys.TEMP_UNIT)
            addAll(CanBusKeys.INDICATORS.keys)
        }
        for (k in keys) {
            val v = runCatching { Settings.System.getString(resolver, k) }.getOrNull()
            if (v != null) out[k] = v
        }
        return out
    }

    private fun readBulk(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            resolver.query(Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)
                ?.use { c ->
                    val ni = c.getColumnIndex("name")
                    val vi = c.getColumnIndex("value")
                    if (ni < 0 || vi < 0) return@use
                    while (c.moveToNext()) {
                        val n = c.getString(ni) ?: continue
                        out[n] = c.getString(vi) ?: ""
                    }
                }
        }
        return out
    }

    /** Registers an observer fired on any Settings.System change. Returns it so it can be freed. */
    fun observe(onChange: () -> Unit): ContentObserver {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        runCatching {
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
        }
        return obs
    }

    fun stopObserving(obs: ContentObserver) {
        runCatching { resolver.unregisterContentObserver(obs) }
    }
}
