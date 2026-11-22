package com.arbelonson.ozen

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class QueuedOnce(private val scope: CoroutineScope, private val work: suspend () -> Unit) {
    private val queued = AtomicBoolean(false)

    fun request() {
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            queued.set(false)
            work()
        }
    }
}
