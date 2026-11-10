package com.arbelonson.ozen.core

class TorchOwnership {
    private var ozenOn = false
    private var ozenOwns = false
    private var personLit = false

    @Synchronized
    fun mayTurnOn(): Boolean = !personLit

    @Synchronized
    fun needsTurningOff(): Boolean = ozenOn

    @Synchronized
    fun switched(on: Boolean, succeeded: Boolean) {
        if (succeeded) {
            ozenOn = on
            if (on) ozenOwns = true
        } else if (on) {
            ozenOn = false
        }
    }

    @Synchronized
    fun watching() {
        ozenOwns = ozenOn
        personLit = false
    }

    @Synchronized
    fun changed(enabled: Boolean) {
        if (enabled) {
            personLit = !ozenOwns
        } else {
            ozenOwns = false
            personLit = false
        }
    }

    @Synchronized
    fun unavailable() {
        ozenOn = false
        ozenOwns = false
        personLit = false
    }
}
