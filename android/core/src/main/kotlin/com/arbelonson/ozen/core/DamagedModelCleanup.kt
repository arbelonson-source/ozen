package com.arbelonson.ozen.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Checks a model file against the published one and removes it when it does not match. */
class DamagedModelCleanup(private val download: PhoneModelDownloader, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Runs to the end even if the caller is cancelled, so a removed file always gets its notice and fresh download. Returns true when the file was damaged and removed; [afterRemoved] runs then. */
    suspend fun run(afterRemoved: () -> Unit): Boolean = withContext(NonCancellable + io) {
        val removed = download.removeIfDamaged()
        if (removed) afterRemoved()
        removed
    }
}
