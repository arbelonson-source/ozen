package com.arbelonson.ozen

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async

internal object HistoryStars {
    private val inOrder = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { work -> Thread(work, "ozen.history-stars").apply { isDaemon = true } }.asCoroutineDispatcher(),
    )

    suspend fun <T> inTurn(work: () -> T): T = inOrder.async { work() }.await()
}
