package com.arbelonson.ozen

class RefusalCard {
    var shown = false
        private set

    fun refused(appIsActive: Boolean, microphoneGranted: Boolean) {
        shown = ServiceRestart.refusalShownOnScreen(appIsActive, restartRefused = true, microphoneGranted = microphoneGranted)
    }

    fun captionsStarted() {
        shown = false
    }
}
