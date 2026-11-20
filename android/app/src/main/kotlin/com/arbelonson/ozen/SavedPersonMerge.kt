package com.arbelonson.ozen

internal object SavedPersonMerge {
    fun target(renaming: String, to: String, savedNames: List<String>): String? {
        val wanted = to.trim().lowercase()
        if (wanted.isEmpty()) return null
        return savedNames.firstOrNull { it != renaming && it.trim().lowercase() == wanted }
    }
}
