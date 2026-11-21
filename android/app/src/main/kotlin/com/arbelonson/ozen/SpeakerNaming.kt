package com.arbelonson.ozen

internal sealed interface SpeakerNaming {
    data class Rename(val from: String) : SpeakerNaming

    data object Unchanged : SpeakerNaming

    data object NewPrint : SpeakerNaming

    companion object {
        fun of(oldName: String?, name: String, savedNames: Set<String>, isRespelling: Boolean): SpeakerNaming {
            if (oldName == null || !isRespelling || isAnotherSavedPerson(oldName, name, savedNames) || oldName !in savedNames) return NewPrint
            return if (name == oldName) Unchanged else Rename(oldName)
        }

        fun movesOnlyTheLine(oldName: String?, name: String, savedNames: Set<String>, asked: Boolean): Boolean =
            asked || (oldName != null && isAnotherSavedPerson(oldName, name, savedNames))

        fun renamedEverywhere(oldName: String?, name: String, savedNames: Set<String>, isRespelling: Boolean): String? =
            oldName?.takeIf { it != name && isRespelling && !isAnotherSavedPerson(it, name, savedNames) && it in savedNames }

        private fun isAnotherSavedPerson(oldName: String, name: String, savedNames: Set<String>): Boolean =
            SavedPersonMerge.target(oldName, name, savedNames) != null
    }
}
