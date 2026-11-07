package com.arbelonson.ozen

object ServiceRestart {
    fun shouldListen(systemRestart: Boolean, mayStartCaptions: Boolean): Boolean = !systemRestart || mayStartCaptions
}
