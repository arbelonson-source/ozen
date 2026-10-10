package com.arbelonson.ozen.core

import kotlin.math.max

enum class DeviceHeat(val rawValue: Int) {
    Nominal(0),
    Fair(1),
    Serious(2),
    Critical(3),
}

object InferenceCadence {
    const val BASE_SECONDS = 0.6

    fun secondsBetweenLivePasses(heat: DeviceHeat, lowPowerMode: Boolean, lastPassSeconds: Double?): Double {
        var interval = when (heat) {
            DeviceHeat.Nominal, DeviceHeat.Fair -> BASE_SECONDS
            DeviceHeat.Serious -> 1.5
            DeviceHeat.Critical -> 4.0
        }
        if (lowPowerMode) {
            interval = max(interval, 1.2)
        }
        if (lastPassSeconds != null && lastPassSeconds.isFinite() && lastPassSeconds > 0) {
            interval = max(interval, lastPassSeconds * 2)
        }
        return interval
    }
}
