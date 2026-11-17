package com.arbelonson.ozen.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.cancellation.CancellationException

/**
 * A scope for work that must outlive a mistake: an error nothing caught goes
 * to [onError] instead of ending the app. Cancellation never reaches it.
 * The handler itself cannot throw: an error in it is dropped, because a
 * second one would go back to ending the app.
 */
fun errorGuardedScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    onError: (Throwable) -> Unit,
): CoroutineScope = CoroutineScope(
    SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) {
            try {
                onError(error)
            } catch (_: Exception) {
            }
        }
    },
)
