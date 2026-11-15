package com.arbelonson.ozen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Waits out the delay before an away notice is posted. A coroutine delay
 * counts processor time, which stops with the screen off and nothing else
 * running, so a minute could pass as many; a partial wake lock is held for
 * the wait, longer than the delay by a margin so it can never be kept for
 * good, and let go the moment the notice is posted, cancelled or replaced.
 */
class DelayedPost(private val scope: CoroutineScope, private val awake: KeptAwake) {
    private var current: Job? = null

    fun schedule(afterSeconds: Double, post: () -> Unit) {
        cancel()
        awake.keepAwake(afterSeconds.seconds + MARGIN)
        val job = scope.launch {
            try {
                delay((afterSeconds * 1000).toLong())
                post()
            } finally {
                if (current === coroutineContext[Job]) {
                    current = null
                    awake.keepAwake(null)
                }
            }
        }
        current = job
    }

    fun cancel() {
        current?.cancel()
        current = null
        awake.keepAwake(null)
    }

    private companion object {
        val MARGIN = 30.seconds
    }
}
