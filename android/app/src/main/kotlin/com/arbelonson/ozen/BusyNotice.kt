package com.arbelonson.ozen

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

    fun clear() {
        raised.value = false
    }
}
