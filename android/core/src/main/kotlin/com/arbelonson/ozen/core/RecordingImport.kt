package com.arbelonson.ozen.core

object RecordingImport {
    private const val SEPARATORS = "[\\s_\\-.,()\\[\\]#]"
    private val trailing = unicodePattern("$SEPARATORS*(?:\\d+(?:$SEPARATORS+\\d+)*)?$SEPARATORS*\$").toRegex()

    fun personName(fromFileName: String): String? {
        var name = withoutExtension(fromFileName)
        name = buildString {
            name.codePoints().forEach { if (!isDirectionMark(it)) appendCodePoint(it) }
        }
        name = trailing.replace(name, "")
        name = name.replace("_", " ").trim { it.isWhitespace() || Character.isSpaceChar(it) }
        return name.ifEmpty { null }
    }

    private fun isDirectionMark(value: Int): Boolean =
        value == 0x061C || value == 0x200E || value == 0x200F || value in 0x202A..0x202E || value in 0x2066..0x2069

    private fun withoutExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && dot < fileName.length - 1) fileName.substring(0, dot) else fileName
    }

    data class Result(
        val added: Map<String, Int> = emptyMap(),
        val unusable: List<String> = emptyList(),
    ) {
        val summary: String
            get() {
                val names = added.entries.sortedBy { it.key }.map { (name, count) ->
                    if (count == 1) name else tr("%1 (%2 הקלטות)", "%1 (%2 recordings)", listOf(name, "$count"))
                }
                val lines = mutableListOf<String>()
                if (names.isNotEmpty()) {
                    lines.add(tr("נוספו: ", "Added: ") + names.joinToString(", "))
                }
                if (unusable.isNotEmpty()) {
                    lines.add(tr("לא נוספו (אין בהן מספיק דיבור, או שאין שם בשם הקובץ): ", "Not added (too little speech, or no name in the file name): ") + unusable.joinToString(", "))
                }
                return lines.joinToString("\n\n")
            }
    }
}
