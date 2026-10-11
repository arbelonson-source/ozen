package com.arbelonson.ozen

import android.os.PowerManager
import com.arbelonson.ozen.core.DeviceHeat
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceHeatTest {
    @Test
    fun `Android's seven heat levels fall into the iPhone's four, slowing the captions only once the phone is held back hard`() {
        val levels = listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )
        assertEquals(
            listOf(DeviceHeat.Nominal, DeviceHeat.Fair, DeviceHeat.Fair, DeviceHeat.Serious, DeviceHeat.Critical, DeviceHeat.Critical, DeviceHeat.Critical),
            levels.map(::deviceHeat),
        )
    }

    @Test
    fun `a level newer than this app is taken as hot`() {
        assertEquals(DeviceHeat.Critical, deviceHeat(PowerManager.THERMAL_STATUS_SHUTDOWN + 1))
        assertEquals(DeviceHeat.Serious, deviceHeat(-1))
    }
}
