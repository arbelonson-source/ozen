package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class BusyNotice {
    private val raised = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = raised

    /** Counts every refusal while the note is up, so each one can be announced again. */
    private val count = MutableStateFlow(0)
    val refusals: StateFlow<Int> = count

    fun accept(busy: Boolean): Boolean {
        raised.value = busy
        if (busy) count.value += 1 else count.value = 0
        return !busy
    }

    fun follow(state: FileTranscription) {
        if (state !is FileTranscription.Working) clear()
    }

    fun clear() {
        raised.value = false
        count.value = 0
    }
}
