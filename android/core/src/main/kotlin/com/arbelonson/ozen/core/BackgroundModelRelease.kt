package com.arbelonson.ozen.core

object BackgroundModelRelease {
    private const val FOUR_GIB = 4L * 1024 * 1024 * 1024
    private const val SMALL_PHONE_SECONDS = 120L
    private const val BIG_PHONE_SECONDS = 600L

    fun delaySeconds(totalMemoryBytes: Long?): Long =
        if (totalMemoryBytes == null || totalMemoryBytes <= FOUR_GIB) SMALL_PHONE_SECONDS else BIG_PHONE_SECONDS

    fun shouldRelease(listening: Boolean, transitioning: Boolean, mayLoadModels: Boolean, serviceRunning: Boolean): Boolean =
        !listening && !transitioning && !mayLoadModels && !serviceRunning
}
