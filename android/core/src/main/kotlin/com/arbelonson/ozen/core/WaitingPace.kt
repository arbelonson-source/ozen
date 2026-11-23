package com.arbelonson.ozen.core

class WaitingPace(
    private val firstSeconds: Double,
    private val steadySeconds: Double = STEADY_SECONDS,
    private val longestSeconds: Double = LONGEST_SECONDS,
    private val keptUpSeconds: Double = KEPT_UP_SECONDS,
    alreadyWaited: Double = 0.0,
) {
    data class Step(val seconds: Double, val keptUp: Boolean, val letsGo: Boolean)

    private var paced = 0.0
    var waited = alreadyWaited
        private set
    private var last = 0.0
    private var letGo = alreadyWaited > keptUpSeconds

    fun next(watched: Boolean = false): Step {
        if (watched) return Step(firstSeconds, keptUp = true, letsGo = false)
        val seconds = if (paced < steadySeconds || last <= 0.0) firstSeconds else minOf(last * 2, maxOf(longestSeconds, firstSeconds))
        val keptUp = waited + seconds <= keptUpSeconds
        val letsGo = !keptUp && !letGo
        if (letsGo) letGo = true
        paced += seconds
        waited += seconds
        last = seconds
        return Step(seconds, keptUp, letsGo)
    }

    fun startOver() {
        paced = 0.0
        last = 0.0
    }

    companion object {
        const val STEADY_SECONDS = 120.0
        const val LONGEST_SECONDS = 300.0
        const val KEPT_UP_SECONDS = 600.0
    }
}
