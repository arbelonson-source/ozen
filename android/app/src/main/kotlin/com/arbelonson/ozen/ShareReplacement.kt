package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class ShareReplacement<T : Any> {
    private val held = MutableStateFlow<T?>(null)
    val waiting: StateFlow<T?> = held
    private var opening = false

    fun arrive(file: T, shown: FileTranscription): Boolean {
        if (opening || !shown.leavingLosesText) return true
        held.value = file
        return false
    }

    fun replace(): T? {
        val file = held.value
        held.value = null
        opening = file != null
        return file
    }

    fun keep() {
        held.value = null
    }

    fun declined() {
        opening = false
    }

    fun follow(shown: FileTranscription): T? {
        if (shown.leavingLosesText) return null
        val file = held.value
        held.value = null
        opening = file != null
        return file
    }
}
