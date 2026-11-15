package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class BusyNotice {
    private val raised = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = raised

    private val count = MutableStateFlow(0)
    val refusals: StateFlow<Int> = count

    private var announced = 0

    fun announcementDue(): Boolean {
        val current = count.value
        val due = current > announced
        announced = current
        return due
    }

    fun accept(busy: Boolean): Boolean {
        raised.value = busy
        if (busy) count.value += 1 else resetCount()
        return !busy
    }

    fun follow(state: FileTranscription) {
        if (state !is FileTranscription.Working) clear()
    }

    fun clear() {
        raised.value = false
        resetCount()
    }

    private fun resetCount() {
        count.value = 0
        announced = 0
    }
}
