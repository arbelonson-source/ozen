package com.arbelonson.ozen.core

data class AlertFlash(val litSeconds: Double, val darkSeconds: Double, val count: Int) {
    val flashesPerSecond: Double get() = 1 / (litSeconds + darkSeconds)

    companion object {
        const val MAXIMUM_FLASHES_PER_SECOND: Double = 3.0

        fun pattern(importance: SoundEvent.Importance, reduceMotion: Boolean): AlertFlash? = when (importance) {
            SoundEvent.Importance.Critical ->
                if (reduceMotion) AlertFlash(4.0, 0.0, 1) else AlertFlash(0.4, 0.4, 6)
            SoundEvent.Importance.High ->
                if (reduceMotion) AlertFlash(1.5, 0.0, 1) else AlertFlash(0.4, 0.4, 2)
            SoundEvent.Importance.Medium, SoundEvent.Importance.Low -> null
        }

        fun takesOver(flashing: SoundEvent.Importance?, arriving: SoundEvent.Importance, reduceMotion: Boolean): Boolean {
            if (pattern(arriving, reduceMotion) == null) return false
            if (flashing == null) return true
            return arriving >= flashing
        }
    }
}
