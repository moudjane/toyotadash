package com.moudjane.toyotadash.car

import com.moudjane.toyotadash.core.dash.DashValues
import com.moudjane.toyotadash.core.dash.DemoDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-wide dashboard state, shared between the tablet UI and the Android Auto surface —
 * the Android counterpart of iOS `DashModel.shared`. The tablet's OBD session pushes decoded
 * values through [update]; the car surface (and, later, the tablet) read [values].
 *
 * For now the car side can drive a self-contained demo so Android Auto shows a moving cockpit
 * without a dongle. Wiring the live `ObdViewModel` session into [update] is the next step.
 */
object DashHub {

    /** Number of kW samples kept for the "last minute" energy graph (~0.5 s apart on iOS). */
    const val HISTORY = 120

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _values = MutableStateFlow<DashValues?>(null)
    val values: StateFlow<DashValues?> = _values.asStateFlow()

    private val historyLock = Any()
    private val _kwHistory = ArrayDeque<Float>()
    /** Battery power over the recent past (oldest first), for the energy graph. */
    val kwHistory: List<Float> get() = synchronized(historyLock) { _kwHistory.toList() }

    private var demoJob: Job? = null

    private fun emit(values: DashValues) {
        _values.value = values
        synchronized(historyLock) {
            _kwHistory.addLast(values.kw ?: 0f)
            while (_kwHistory.size > HISTORY) _kwHistory.removeFirst()
        }
    }

    /** Push decoded values from the live OBD session. Cancels the stand-in demo. */
    fun update(values: DashValues) {
        stopDemo()
        emit(values)
    }

    fun clear() {
        stopDemo()
        _values.value = null
        synchronized(historyLock) { _kwHistory.clear() }
    }

    /** Self-driven demo so Android Auto shows a moving cockpit without a dongle. */
    fun startDemo() {
        if (demoJob != null) return
        demoJob = scope.launch {
            var tick = 0
            while (isActive) {
                val f = DemoDrive.frame(++tick)
                emit(DashValues.from(f.standard, f.toyota, 14.1f, f.driveSelect))
                delay(100)
            }
        }
    }

    fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
    }
}
