package com.arbelonson.ozen.core

object TorchFlash {
    data class Step(val onSeconds: Double, val offSeconds: Double)

    fun pattern(importance: SoundEvent.Importance, reduceMotion: Boolean): List<Step>? = when (importance) {
        SoundEvent.Importance.Critical ->
            if (reduceMotion) List(3) { Step(2.0, 1.5) } else List(8) { Step(0.7, 0.5) }
        SoundEvent.Importance.High ->
            if (reduceMotion) List(2) { Step(1.5, 1.5) } else List(4) { listOf(Step(0.2, 0.3), Step(0.2, 1.8)) }.flatten()
        SoundEvent.Importance.Medium, SoundEvent.Importance.Low -> null
    }

    fun sample(reduceMotion: Boolean): List<Step> {
        val door = pattern(SoundEvent.Importance.High, reduceMotion) ?: emptyList()
        return if (reduceMotion) door else door.take(4)
    }

    fun shouldFlash(
        isOn: Boolean,
        importance: SoundEvent.Importance,
        appIsActive: Boolean,
        quietHours: QuietHours,
        now: Double,
        utcOffsetSeconds: Int,
    ): Boolean {
        if (!isOn || appIsActive) return false
        return when (importance) {
            SoundEvent.Importance.Critical -> true
            SoundEvent.Importance.High -> !quietHours.isQuiet(now, utcOffsetSeconds)
            SoundEvent.Importance.Medium, SoundEvent.Importance.Low -> false
        }
    }
}
