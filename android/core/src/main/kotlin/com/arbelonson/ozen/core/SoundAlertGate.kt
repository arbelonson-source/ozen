package com.arbelonson.ozen.core

class SoundAlertGate {
    private var skipped = false

    fun pass(chunk: FloatArray, alertsOn: Boolean): List<FloatArray> {
        if (!alertsOn) {
            skipped = true
            return emptyList()
        }
        if (!skipped) return listOf(chunk)
        skipped = false
        return listOf(FloatArray(0), chunk)
    }
}
