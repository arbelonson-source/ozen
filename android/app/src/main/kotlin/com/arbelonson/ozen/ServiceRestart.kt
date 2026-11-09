package com.arbelonson.ozen

object ServiceRestart {
    fun shouldListen(systemRestart: Boolean, mayStartCaptions: Boolean, soundsOnly: Boolean = false): Boolean =
        !systemRestart || mayStartCaptions || soundsOnly

    fun soundsOnlyAfterRestart(systemRestart: Boolean, chosenBeforeKill: Boolean): Boolean = systemRestart && chosenBeforeKill
}
