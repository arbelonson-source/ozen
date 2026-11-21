package com.arbelonson.ozen.core

class FixedLinesSoFar(private val fix: (List<WordFix>, String) -> String = WordFixes::apply) {
    private var fixedWith: List<WordFix>? = null
    private var shown: List<TranscribedLine> = emptyList()

    fun of(heard: List<TranscribedLine>, fixes: List<WordFix>): List<TranscribedLine> {
        if (fixes != fixedWith || heard.size < shown.size) {
            shown = heard.map { fixed(it, fixes) }
            fixedWith = fixes
        } else if (heard.size > shown.size) {
            shown = shown + heard.subList(shown.size, heard.size).map { fixed(it, fixes) }
        }
        return shown
    }

    private fun fixed(line: TranscribedLine, fixes: List<WordFix>) = line.copy(text = fix(fixes, line.text))
}
