package com.arbelonson.ozen

internal object BigTextClear {
    fun cleared(text: String, stored: String?): String? = if (text.isBlank()) stored else text

    fun restorable(text: String, stored: String?): Boolean = stored != null && text.isEmpty()

    fun typed(text: String, stored: String?): String? = if (text.isEmpty()) stored else null
}
