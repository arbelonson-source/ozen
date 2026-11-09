package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SilencePhraseGuardTest {
    private fun line(text: String, id: UUID = UUID.randomUUID(), final: Boolean = true, voiced: Int? = null, at: Double): Pair<TranscriptToken, Double> =
        TranscriptToken(utteranceID = id, text = text, isFinal = final, timestamp = at, voicedChunks = voiced) to at

    private fun admitted(lines: List<Pair<TranscriptToken, Double>>): List<Boolean> {
        val guardian = SilencePhraseGuard()
        return lines.map { guardian.admits(it.first, it.second) }
    }

    @Test
    fun `thanks repeated within one line is never shown`() {
        assertEquals(
            listOf(false, false, false),
            admitted(
                listOf(
                    line("תודה. תודה. תודה.", at = 10.0),
                    line("תודה רבה, תודה רבה", at = 200.0),
                    line("Thank you. Thank you.", at = 400.0),
                ),
            ),
        )
    }

    @Test
    fun `a lone thanks shows once, the same again within seconds with nothing said between does not, and a run of them stays hidden`() {
        val first = UUID.randomUUID()
        assertEquals(
            listOf(true, true, true, false, false, false, true),
            admitted(
                listOf(
                    line("תודה.", id = first, final = false, at = 0.0),
                    line("תודה.", id = first, at = 1.0),
                    line(" ... ", at = 5.0),
                    line("תודה.", at = 12.0),
                    line("תודה רבה", at = 24.0),
                    line("תודה.", at = 36.0),
                    line("תודה.", at = 60.0),
                ),
            ),
        )
    }

    @Test
    fun `two real thank-yous half a minute apart both show`() {
        assertEquals(
            listOf(true, true),
            admitted(listOf(line("תודה", at = 0.0), line("תודה רבה", at = 30.0))),
        )
    }

    @Test
    fun `thanks with a name that is also a sound tag is someone talking, so it shows, a line of only sign-offs or only tags still doesn't`() {
        assertEquals(
            listOf(true, true, false, false),
            admitted(
                listOf(
                    line("תודה שירה", at = 0.0),
                    line("שירה, תודה רבה!", at = 100.0),
                    line("מוזיקה. שירה.", at = 200.0),
                    line("תודה רבה, צפייה מהנה", at = 300.0),
                ),
            ),
        )
    }

    @Test
    fun `real words in between, or grown into a sentence, show as usual`() {
        val growing = UUID.randomUUID()
        assertEquals(
            listOf(true, true, true, false, true, true),
            admitted(
                listOf(
                    line("תודה", at = 0.0),
                    line("בבקשה, אין על מה", at = 5.0),
                    line("תודה", at = 8.0),
                    line("תודה", id = growing, final = false, at = 9.0),
                    line("תודה רבה על הארוחה", id = growing, at = 10.0),
                    line("תודה תודה לסבתא", at = 11.0),
                ),
            ),
        )
    }

    @Test
    fun `the same thanks said twice or three times in one clearly voiced breath shows`() {
        assertEquals(listOf(true), admitted(listOf(line("תודה, תודה", voiced = 5, at = 0.0))))
        assertEquals(listOf(true), admitted(listOf(line("Thank you, thank you", voiced = 5, at = 0.0))))
        assertEquals(listOf(true), admitted(listOf(line("תודה. תודה. תודה.", voiced = 6, at = 0.0))))
    }

    @Test
    fun `a repeated thanks with no voice count, or under two voiced chunks per thanks, stays hidden`() {
        assertEquals(listOf(false), admitted(listOf(line("תודה, תודה", at = 0.0))))
        assertEquals(listOf(false), admitted(listOf(line("תודה, תודה", voiced = 3, at = 0.0))))
        assertEquals(listOf(false), admitted(listOf(line("תודה. תודה. תודה.", voiced = 5, at = 0.0))))
    }

    @Test
    fun `sound tags and mixed sign-offs stay hidden however much voice was heard`() {
        assertEquals(
            listOf(false, false, false),
            admitted(
                listOf(
                    line("שירה, שירה", voiced = 10, at = 0.0),
                    line("מוזיקה. מוזיקה.", voiced = 10, at = 100.0),
                    line("תודה רבה, צפייה מהנה", voiced = 10, at = 200.0),
                ),
            ),
        )
    }

    @Test
    fun `a voiced repeat seconds after a shown thanks, or a lone thanks seconds after a shown repeat, is still a thanks coming back`() {
        assertEquals(
            listOf(true, false),
            admitted(listOf(line("תודה", at = 0.0), line("תודה, תודה", voiced = 5, at = 5.0))),
        )
        assertEquals(
            listOf(true, false),
            admitted(listOf(line("תודה, תודה", voiced = 5, at = 0.0), line("תודה", at = 5.0))),
        )
    }

    @Test
    fun `a shown thanks that grows into a clearly voiced repeat on the same line keeps showing`() {
        val growing = UUID.randomUUID()
        assertEquals(
            listOf(true, true),
            admitted(
                listOf(
                    line("תודה", id = growing, final = false, voiced = 2, at = 0.0),
                    line("תודה, תודה", id = growing, voiced = 5, at = 1.0),
                ),
            ),
        )
    }
}
