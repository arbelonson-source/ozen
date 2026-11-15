package com.arbelonson.ozen

import com.arbelonson.ozen.core.FileTranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decides whether a file shared in while a finished transcription is on the
 * screen may take its place at once. Text that only the screen holds (History
 * off, or the save failed) is not replaced without asking, the same as Close.
 */
internal class ShareReplacement<T : Any> {
    private val held = MutableStateFlow<T?>(null)
    val waiting: StateFlow<T?> = held

    /** True when [file] may open now; false when it is held until the person chooses. */
    fun arrive(file: T, shown: FileTranscription): Boolean {
        if (!shown.leavingLosesText) return true
        held.value = file
        return false
    }

    /** The held file, to open in place of the text; the question is over. */
    fun replace(): T? = held.value.also { held.value = null }

    fun keep() {
        held.value = null
    }

    fun follow(shown: FileTranscription) {
        if (!shown.leavingLosesText) held.value = null
    }
}
