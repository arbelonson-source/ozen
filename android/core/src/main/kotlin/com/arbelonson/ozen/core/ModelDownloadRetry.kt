package com.arbelonson.ozen.core

object ModelDownloadRetry {
    private val delays = AutoRecoveryPolicy().downloadDelays.map { it.toLong() }

    fun delaySeconds(failures: Int): Long? = delays.getOrNull(failures - 1)
}
