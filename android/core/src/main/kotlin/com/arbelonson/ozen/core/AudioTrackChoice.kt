package com.arbelonson.ozen.core

object AudioTrackChoice {
    fun index(defaults: List<Boolean>): Int? =
        defaults.indexOf(true).takeIf { it >= 0 } ?: if (defaults.isEmpty()) null else 0
}
