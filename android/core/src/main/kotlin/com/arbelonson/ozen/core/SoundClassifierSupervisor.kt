package com.arbelonson.ozen.core

class SoundClassifierSupervisor(private val budget: RestartBudget, firstLoadIsFree: Boolean = true) {
    enum class Step { Classify, Restart, Skip }

    var isAvailable = true
        private set
    private var workingSince: Double? = null
    private var reported = true
    private var firstLoadPending = firstLoadIsFree

    fun step(hasWorkingClassifier: Boolean, now: Double): Step {
        if (hasWorkingClassifier) {
            val since = workingSince ?: now
            workingSince = since
            if (!isAvailable && now - since >= PROVEN_SECONDS) isAvailable = true
            return Step.Classify
        }
        workingSince = null
        if (firstLoadPending) {
            firstLoadPending = false
            return Step.Restart
        }
        if (budget.spend(now)) return Step.Restart
        isAvailable = false
        return Step.Skip
    }

    fun audioGap() {
        workingSince = null
    }

    fun restartGaveNoClassifier() {
        workingSince = null
        isAvailable = false
    }

    fun takeChange(): Boolean? {
        if (isAvailable == reported) return null
        reported = isAvailable
        return isAvailable
    }

    companion object {
        const val PROVEN_SECONDS = 20.0
    }
}
