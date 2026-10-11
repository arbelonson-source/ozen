package com.arbelonson.ozen

internal sealed interface SpeakerNaming {
    data class Rename(val from: String) : SpeakerNaming

    data object Unchanged : SpeakerNaming

    data object NewPrint : SpeakerNaming

    companion object {
        fun of(oldName: String?, name: String, savedNames: Set<String>): SpeakerNaming = when {
            oldName == null || oldName !in savedNames -> NewPrint
            name == oldName -> Unchanged
            name in savedNames -> NewPrint
            else -> Rename(oldName)
        }
    }
}
