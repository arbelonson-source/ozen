package com.arbelonson.ozen.core

class RestartBudget(
    val limit: Int,
    val windowSeconds: Double,
    val minimumSpacingSeconds: Double = 0.0,
) {
    private val attempts = mutableListOf<Double>()

    fun spend(now: Double): Boolean {
        attempts.removeAll { now - it >= windowSeconds || it > now }
        if (attempts.size >= limit) return false
        val last = attempts.lastOrNull()
        if (last != null && now - last < minimumSpacingSeconds) return false
        attempts.add(now)
        return true
    }
}
