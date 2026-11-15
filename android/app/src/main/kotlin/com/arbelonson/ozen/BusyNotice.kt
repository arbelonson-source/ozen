package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class BusyNotice {
    private val raised = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = raised

    fun accept(busy: Boolean): Boolean {
        raised.value = busy
        return !busy
    }

    fun follow(state: FileTranscription) {
        if (state !is FileTranscription.Working) raised.value = false
    }

    fun clear() {
        raised.value = false
    }
}
