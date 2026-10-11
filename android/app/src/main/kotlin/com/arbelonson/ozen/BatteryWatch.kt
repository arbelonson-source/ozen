package com.arbelonson.ozen

import com.arbelonson.ozen.core.BatteryAdvisor
import com.arbelonson.ozen.core.BatteryWarning

class BatteryWatch(private val show: (BatteryWarning?) -> Unit, private val warned: (BatteryWarning) -> Unit) {
    private val advisor = BatteryAdvisor()
    private var listening = false

    fun listeningChanged(now: Boolean): Boolean {
        if (now == listening) return false
        listening = now
        if (!now) show(null)
        return now
    }

    fun reading(level: Float?, pluggedIn: Boolean, listening: Boolean) {
        listeningChanged(listening)
        if (!listening) return
        if (pluggedIn) show(null)
        val warning = advisor.update(level, pluggedIn) ?: return
        show(warning)
        warned(warning)
    }
}
