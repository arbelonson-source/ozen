package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileTranscriptionJob(
    private val shown: MutableStateFlow<FileTranscription>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(working: FileTranscription.Working, backTo: () -> FileTranscription?, work: suspend () -> Unit) {
        shown.value = working
        val launched = scope.launch(dispatcher) { work() }
        job = launched
        launched.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                shown.update { current -> if (current is FileTranscription.Working) backTo() ?: current else current }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
