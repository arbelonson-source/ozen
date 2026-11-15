package com.arbelonson.ozen

object ServiceRestart {
    fun shouldListen(systemRestart: Boolean, mayStartCaptions: Boolean, soundsOnly: Boolean = false): Boolean =
        !systemRestart || mayStartCaptions || soundsOnly

    fun soundsOnlyAfterRestart(systemRestart: Boolean, chosenBeforeKill: Boolean): Boolean = systemRestart && chosenBeforeKill

    /** Whether the caption screen already tells her captions are off, so no notice is needed while it is in front. */
    fun screenSaysCaptionsAreOff(appIsActive: Boolean, restartRefused: Boolean, microphoneGranted: Boolean): Boolean =
        appIsActive && !(restartRefused && microphoneGranted)
}
