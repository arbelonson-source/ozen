package com.arbelonson.ozen.core

data class AlertVibration(val pulses: List<Pulse>) {
    data class Pulse(val start: Double, val duration: Double, val intensity: Float, val sharpness: Float) {
        val end: Double get() = start + duration
    }

    val totalSeconds: Double get() = pulses.maxOfOrNull { it.end } ?: 0.0

    companion object {
        fun pattern(importance: SoundEvent.Importance): AlertVibration = when (importance) {
            SoundEvent.Importance.Critical -> AlertVibration(
                generateSequence(0) { it + 1 }
                    .map { it * 0.75 }
                    .takeWhile { it < 3.5 }
                    .map { Pulse(it, 0.6, 1f, 0.8f) }
                    .toList(),
            )
            SoundEvent.Importance.High -> AlertVibration(
                listOf(0.0, 0.3, 1.0, 1.3).map { Pulse(it, 0.16, 1f, 1f) },
            )
            SoundEvent.Importance.Medium, SoundEvent.Importance.Low -> AlertVibration(
                listOf(0.0, 0.32).map { Pulse(it, 0.2, 1f, 0.8f) },
            )
        }

        val keyword: AlertVibration = AlertVibration(
            listOf(0.0, 0.24, 0.48).map { Pulse(it, 0.13, 1f, 0.6f) },
        )

        val speechResumed: AlertVibration = AlertVibration(
            listOf(Pulse(0.0, 0.3, 0.7f, 0.2f)),
        )
    }
}
