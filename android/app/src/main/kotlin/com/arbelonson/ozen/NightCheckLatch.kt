package com.arbelonson.ozen

class NightCheckLatch {
    private var claimed = false

    fun nightStart(on: Boolean) {
        if (!on) claimed = false
    }

    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
