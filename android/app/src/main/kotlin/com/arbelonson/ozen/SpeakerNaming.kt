package com.arbelonson.ozen

internal sealed interface SpeakerNaming {
    data class Rename(val from: String) : SpeakerNaming

    data object Unchanged : SpeakerNaming

    data object NewPrint : SpeakerNaming

    companion object {
        fun of(oldName: String?, name: String, savedNames: Set<String>, isRespelling: Boolean): SpeakerNaming {
            val anotherSavedPerson = name != oldName && name in savedNames
            if (oldName == null || !isRespelling || anotherSavedPerson || oldName !in savedNames) return NewPrint
            return if (name == oldName) Unchanged else Rename(oldName)
        }
    }
}
