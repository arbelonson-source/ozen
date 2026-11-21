package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr

internal object SavedPersonMerge {
    fun target(renaming: String?, to: String, savedNames: Collection<String>): String? {
        val wanted = to.trim().lowercase()
        if (wanted.isEmpty()) return null
        return savedNames.firstOrNull { it != renaming && it.trim().lowercase() == wanted }
    }

    fun destination(renaming: String?, to: String, savedNames: Collection<String>): String =
        target(renaming, to, savedNames) ?: to.trim()

    fun question(target: String): Pair<String, String> {
        val name = target.trim()
        return tr("למזג עם %1?", "Merge with %1?", listOf(name)) to
            tr("%1 כבר שמור. שני הקולות יוצגו תחת השם הזה, גם בשיחות קודמות.", "%1 is already saved. Both voices will then be shown under that one name, including in past conversations.", listOf(name))
    }
}
