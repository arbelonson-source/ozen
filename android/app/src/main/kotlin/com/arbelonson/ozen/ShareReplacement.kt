package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class ShareReplacement<T : Any> {
    private val held = MutableStateFlow<T?>(null)
    val waiting: StateFlow<T?> = held

    fun arrive(file: T, shown: FileTranscription): Boolean {
        if (!shown.leavingLosesText) return true
        held.value = file
        return false
    }

    fun replace(): T? = held.value.also { held.value = null }

    fun keep() {
        held.value = null
    }

    fun follow(shown: FileTranscription) {
        if (!shown.leavingLosesText) held.value = null
    }
}
