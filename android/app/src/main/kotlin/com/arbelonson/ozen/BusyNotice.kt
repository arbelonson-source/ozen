package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class BusyNotice {
    private val raised = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = raised

    /** Whether a newly shared recording may be taken. */
    fun accept(busy: Boolean): Boolean {
        raised.value = busy
        return !busy
    }

    /** The notice belongs to a run in progress: any other page of the screen drops it. */
    fun follow(state: FileTranscription) {
        if (state !is FileTranscription.Working) raised.value = false
    }

    fun clear() {
        raised.value = false
    }
}
