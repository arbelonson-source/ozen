package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordAlertMatcherTest {
    private fun alert(phrase: String, isEnabled: Boolean = true) = KeywordAlert(phrase = phrase, isEnabled = isEnabled)

    private fun hits(name: String, caption: String): Int =
        KeywordAlertMatcher(listOf(KeywordAlert(phrase = name))).matches(caption).size

    @Test
    fun `an exact word match is found`() {
        val grandma = alert("סבתא")
        val matcher = KeywordAlertMatcher(listOf(grandma))
        val matches = matcher.matches("היום סבתא באה לבקר")
        assertEquals(1, matches.size)
        assertEquals(grandma.id, matches[0].alertID)
        assertEquals("סבתא", matches[0].phrase)
        assertEquals("סבתא", matches[0].matchedText)
        assertEquals(1, matches[0].wordIndex)
    }

    @Test
    fun `a name behind the invisible direction mark the Hebrew model starts some lines with is still found`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        for (mark in listOf("‫", "‏", "‪", "⁧")) {
            assertEquals(1, matcher.matches(mark + "סבתא, בואי לאכול").size, "U+${mark.codePointAt(0).toString(16)}")
            assertEquals(1, matcher.matches("בואי " + mark + "סבתא‬").size)
        }
    }

    @Test
    fun `a name pasted with an invisible character, or heard with one glued on, is still found`() {
        for (mark in listOf("​", "﻿", "⁠", "­", "‌", "‍", "️")) {
            val label = "U+${mark.codePointAt(0).toString(16)}"
            assertEquals(1, KeywordAlertMatcher(listOf(alert("סבתא" + mark))).matches("בואי סבתא").size, "phrase $label")
            assertEquals(1, KeywordAlertMatcher(listOf(alert("סבתא"))).matches("בואי " + mark + "סבתא" + mark).size, "caption $label")
        }
    }

    @Test
    fun `every single-letter attached prefix matches the stem`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        for (letter in listOf("ו", "ה", "ב", "ל", "מ", "ש", "כ")) {
            val word = letter + "סבתא"
            val matches = matcher.matches("שלום $word שלום")
            assertEquals(1, matches.size, "prefix $letter should match")
            assertEquals(word, matches.firstOrNull()?.matchedText)
        }
    }

    @Test
    fun `stacked attached prefixes match the stem`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        for (stacked in listOf("וה", "כש", "וכש")) {
            val word = stacked + "סבתא"
            val matches = matcher.matches("אתמול $word ישנה")
            assertEquals(1, matches.size, "stacked prefix $stacked should match")
        }
    }

    @Test
    fun `a prefix that stacks on the article matches the stem`() {
        val matcher = KeywordAlertMatcher(listOf(alert("רופא")))
        for (stacked in listOf("כשה", "וכשה", "ומה", "שמה")) {
            val word = stacked + "רופא"
            val matches = matcher.matches("אתמול $word אמר לחכות")
            assertEquals(1, matches.size, "stacked prefix $stacked should match")
            assertEquals(word, matches.firstOrNull()?.matchedText)
        }
    }

    @Test
    fun `a name starting with the letter he does not fire on an everyday word that only looks like it with a preposition swapped in`() {
        val matcher = KeywordAlertMatcher(listOf(alert("הילה"), alert("הלל")))
        assertTrue(matcher.matches("לילה טוב, זה בכלל לא חשוב").isEmpty())
        assertEquals(2, matcher.matches("להילה ולהלל").size)
    }

    @Test
    fun `a suffix change is not treated as a match`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertTrue(matcher.matches("כל הסבתאות באו").isEmpty())
    }

    @Test
    fun `a shorter name inside a longer one is not a match`() {
        val matcher = KeywordAlertMatcher(listOf(alert("דן")))
        assertTrue(matcher.matches("דנה הגיעה הביתה").isEmpty())
    }

    @Test
    fun `a geresh-marked affectionate nickname ending on the configured word matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        val matches = matcher.matches("היום סבתא'לה הגיעה")
        assertEquals(1, matches.size)
        assertEquals("סבתא'לה", matches[0].matchedText)
    }

    @Test
    fun `a plain apostrophe marks the same affectionate nickname ending`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertEquals(1, matcher.matches("היום סבתא'לה הגיעה").size)
    }

    @Test
    fun `an attached preposition still applies before a nickname ending`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        val matches = matcher.matches("דיברתי לסבתא'לה אתמול")
        assertEquals(1, matches.size)
        assertEquals("לסבתא'לה", matches[0].matchedText)
    }

    @Test
    fun `the nickname ending written with its mark after the lamed matches too, and a name ending in lamed keeps its own`() {
        val savta = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertEquals(listOf("סבתאל'ה"), savta.matches("היום סבתאל'ה הגיעה").map { it.matchedText })
        assertEquals(listOf("לסבתאל׳ה"), savta.matches("דיברתי לסבתאל׳ה אתמול").map { it.matchedText })
        assertEquals(1, KeywordAlertMatcher(listOf(alert("אמא"))).matches("אמאל'ה, בואי").size)
        val michal = KeywordAlertMatcher(listOf(alert("מיכל")))
        assertEquals(1, michal.matches("מיכל'ה הגיעה").size)
        assertTrue(savta.matches("סבתאל'ים הגיעו").isEmpty())
    }

    @Test
    fun `a genuine suffix change is still not a match, unlike a marked nickname ending`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertTrue(matcher.matches("כל הסבתאות באו").isEmpty())
    }

    @Test
    fun `a curated nickname unrelated in spelling to the configured word still matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertEquals(1, matcher.matches("היי סבתוש מה נשמע").size)
        assertEquals(1, matcher.matches("'סבתוש' הגיעה").size)
        assertEquals(1, matcher.matches("'סבתא'לה' הגיעה").size)
    }

    @Test
    fun `a curated nickname behind an attached prefix still matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertEquals(1, matcher.matches("כשסבתוש הגיעה").size)
        assertEquals(1, matcher.matches("תגידו לסבתושה").size)
        assertEquals(1, KeywordAlertMatcher(listOf(alert("אמא"))).matches("הלכתי לאימא").size)
    }

    @Test
    fun `the alternate spelling ima-ama matches in either direction`() {
        assertEquals(1, KeywordAlertMatcher(listOf(alert("אמא"))).matches("איפה אימא שלי").size)
        assertEquals(1, KeywordAlertMatcher(listOf(alert("אימא"))).matches("איפה אמא שלי").size)
    }

    @Test
    fun `an unrelated short configured word does not gain a nickname match from another word's ending`() {
        val matcher = KeywordAlertMatcher(listOf(alert("דן")))
        assertTrue(matcher.matches("סבתא'לה הגיעה").isEmpty())
    }

    @Test
    fun `niqqud on the caption does not block a match against a plain phrase`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        val matches = matcher.matches("היום סָבְתָא הגיעה")
        assertEquals(1, matches.size)
    }

    @Test
    fun `niqqud on the phrase itself does not block a match`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סָבְתָא")))
        val matches = matcher.matches("היום סבתא הגיעה")
        assertEquals(1, matches.size)
    }

    @Test
    fun `punctuation and quotes around the word do not block a match`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא")))
        assertEquals(1, matcher.matches("היום סבתא, הגיעה").size)
        assertEquals(1, matcher.matches("היום \"סבתא\" הגיעה").size)
        assertEquals(1, matcher.matches("היום סבתא! הגיעה").size)
    }

    @Test
    fun `a Latin phrase matches regardless of case`() {
        val matcher = KeywordAlertMatcher(listOf(alert("dana")))
        val matches = matcher.matches("I saw Dana today")
        assertEquals(1, matches.size)
        assertEquals("Dana", matches[0].matchedText)
    }

    @Test
    fun `a multi-word phrase matches only when its words are consecutive`() {
        val matcher = KeywordAlertMatcher(listOf(alert("בית חולים")))
        assertFalse(matcher.matches("נסענו לבית חולים דחוף").isEmpty())
        assertTrue(matcher.matches("נסענו לבית גדול וגם חולים").isEmpty())
    }

    @Test
    fun `a multi-word phrase matches across a standalone punctuation token`() {
        val matcher = KeywordAlertMatcher(listOf(alert("בית חולים")))
        val matches = matcher.matches("נסענו לבית , חולים דחוף")
        assertEquals(1, matches.size)
        assertEquals("לבית חולים", matches[0].matchedText)
        assertEquals(1, matches[0].wordIndex)
    }

    @Test
    fun `an attached prefix on the first word of a multi-word phrase still matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("בית חולים")))
        val matches = matcher.matches("נסענו לבית חולים דחוף")
        assertEquals(1, matches.size)
        assertEquals("לבית חולים", matches[0].matchedText)
        assertEquals(1, matches[0].wordIndex)
    }

    @Test
    fun `a nickname ending on a later word of a multi-word phrase still matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא רחל")))
        assertEquals(listOf("סבתא רחל'ה"), matcher.matches("בואי סבתא רחל'ה").map { it.matchedText })
        assertEquals(listOf("סבתא רחל׳ה"), matcher.matches("בואי סבתא רחל׳ה").map { it.matchedText })
    }

    @Test
    fun `two occurrences of the same keyword yield two matches in caption order`() {
        val grandma = alert("סבתא")
        val matcher = KeywordAlertMatcher(listOf(grandma))
        val matches = matcher.matches("סבתא אמרה לסבתא שלום")
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].wordIndex)
        assertEquals("סבתא", matches[0].matchedText)
        assertEquals(2, matches[1].wordIndex)
        assertEquals("לסבתא", matches[1].matchedText)
    }

    @Test
    fun `a disabled alert never matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("סבתא", isEnabled = false)))
        assertTrue(matcher.matches("היום סבתא הגיעה").isEmpty())
    }

    @Test
    fun `a blank phrase never matches`() {
        val matcher = KeywordAlertMatcher(listOf(alert("   ")))
        assertTrue(matcher.matches("היום סבתא הגיעה").isEmpty())
    }

    @Test
    fun `matches from two different alerts in one caption are both reported in caption order`() {
        val grandma = alert("סבתא")
        val ambulance = alert("אמבולנס")
        val matcher = KeywordAlertMatcher(listOf(ambulance, grandma))
        val matches = matcher.matches("סבתא קראה לאמבולנס")
        assertEquals(2, matches.size)
        assertEquals(grandma.id, matches[0].alertID)
        assertEquals(0, matches[0].wordIndex)
        assertEquals(ambulance.id, matches[1].alertID)
        assertEquals(2, matches[1].wordIndex)
    }

    @Test
    fun `a short name glued to a prefix that spells an everyday word does not fire, the name itself and other prefixes still do`() {
        assertEquals(0, hits("לי", "זה הספר שלי"))
        assertEquals(0, hits("לי", "קפה בלי סוכר"))
        assertEquals(0, hits("בן", "חולצה לבן"))
        assertEquals(0, hits("שיר", "היא אוהבת לשיר"))
        assertEquals(0, hits("גיל", "בגיל שמונים"))
        assertEquals(1, hits("לי", "לי, בואי רגע"))
        assertEquals(1, hits("לי", "תגידו לְלי שלום"))
        assertEquals(1, hits("טל", "אמא וטל באו"))
        assertEquals(0, hits("טל", "הטיסה בטל"))
        assertEquals(1, hits("דן", "תגידו לדן"))
        assertEquals(0, hits("גיל", "תביאו לגיל ולמיכל"))
    }

    @Test
    fun `Sarah, Hana, David and Ron don't fire on job, kosher, the minister, camp, the uncle or Sharon, called by name they still do`() {
        assertEquals(0, hits("שרה", "היא מצאה משרה חדשה"))
        assertEquals(0, hits("שרה", "המסעדה כשרה"))
        assertEquals(0, hits("שרה", "השרה אמרה היום"))
        assertEquals(0, hits("חנה", "מחנה קיץ בגליל"))
        assertEquals(0, hits("דוד", "הדוד שלי בא"))
        assertEquals(0, hits("רון", "שרון הגיעה"))
        assertEquals(1, hits("שרה", "שרה, בואי לאכול"))
        assertEquals(1, hits("שרה", "תגידו לשרה"))
        assertEquals(1, hits("חנה", "אמא וחנה באו"))
        assertEquals(1, hits("דוד", "תביאו לדוד"))
        assertEquals(1, hits("רון", "רון הגיע"))
    }

    @Test
    fun `names that real speech hid in everyday words, Avi in 'pains of', Leah in 'full', Beni in 'buildings of', Miriam after 'the'`() {
        assertEquals(0, hits("אבי", "באמת חוויה טראומטית של כאבי תופת כאן"))
        assertEquals(0, hits("אבי", "יש לה כאבי ראש וכאבי גב"))
        assertEquals(0, hits("לאה", "או לא לעבוד משרה מלאה, להכניס פחות."))
        assertEquals(0, hits("לאה", "הכוס שמלאה במים, ומלאה עד הסוף"))
        assertEquals(0, hits("בני", "נזכיר את מבני השיעור ומבני הציבור"))
        assertEquals(0, hits("מרים", "המרים, שקיבלה תשובה"))
        assertEquals(0, hits("מרים", "והמרים מהמשקל, שהמרים"))
        assertEquals(1, hits("אבי", "אבי, בוא לאכול"))
        assertEquals(1, hits("אבי", "תתקשרו לאבי"))
        assertEquals(1, hits("לאה", "אמא ולאה באו"))
        assertEquals(1, hits("בני", "תגידו לבני שהגענו"))
        assertEquals(1, hits("מרים", "מרים, את שומעת?"))
    }

    @Test
    fun `a word a live pass cut off half way is not taken for a name`() {
        val matcher = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "טל"), KeywordAlert(phrase = "סבתא")))
        assertTrue(matcher.matchesInLiveText("כדי שכשנלחץ על הטל...").isEmpty())
        assertTrue(matcher.matchesInLiveText("על הטל…").isEmpty())
        assertTrue(matcher.matchesInLiveText("על הטל ...").isEmpty())
        assertEquals(listOf("סבתא"), matcher.matchesInLiveText("סבתא, תראי את הטל...").map { it.phrase })
        assertEquals(listOf("טל"), matcher.matchesInLiveText("בוא הנה טל").map { it.phrase })
        assertEquals(listOf("טל"), matcher.matches("בוא הנה טל...").map { it.phrase })
    }

    @Test
    fun `the suggested words 'medicine' and 'doctor' also fire on their plural and feminine forms, with a prefix too`() {
        assertEquals(1, hits("תרופה", "לקחת את התרופות?"))
        assertEquals(1, hits("רופא", "הרופאה אמרה שזה בסדר"))
        assertEquals(1, hits("רופא", "היינו אצל הרופאים"))
        assertEquals(1, hits("רופא", "תור לרופאת משפחה"))
        assertEquals(1, hits("תרופה", "איפה התרופה"))
        assertEquals(0, hits("סבתא", "סבתות"))
    }

    @Test
    fun `a two-part name matches whether the caption writes it as one word or two, and the other way round`() {
        assertEquals(1, hits("בן ציון", "בנציון הגיע"))
        assertEquals(1, hits("בן-ציון", "בנציון הגיע"))
        assertEquals(1, hits("בנציון", "בן ציון הגיע"))
        assertEquals(1, hits("בנציון", "בן-ציון הגיע"))
        assertEquals(1, hits("בנציון", "תגידו לבן ציון"))
        assertEquals(1, hits("בן ציון", "תגידו לבנציון"))
        assertEquals(1, hits("בן ציון", "בן ציון הגיע"))
        assertEquals(1, hits("בנציון", "בנציון הגיע"))
        assertEquals(1, hits("בתחן", "בת חן הגיעה"))
        assertEquals(1, hits("בן ציון", "בנציון!"))
        assertEquals(1, hits("בן-ציון", "בנציון"))
        assertEquals(0, hits("בן", "בנציון הגיע"))
        assertEquals(0, hits("ציון", "בנציון הגיע"))
        assertEquals(0, hits("בן ציון", "בנציונה הגיעה"))
        assertEquals(0, hits("שירלי", "תכתוב שיר לי"))
        assertEquals(1, hits("שירלי", "שירלי באה"))
    }

    @Test
    fun `both spellings of a two-part name on the list fire once for one mention, not twice`() {
        val both = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "בנציון"), KeywordAlert(phrase = "בן ציון")))
        assertEquals(1, both.matches("בנציון הגיע").size)
        assertEquals(1, both.matches("בן ציון הגיע").size)
        val nested = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "סבתא"), KeywordAlert(phrase = "סבתא רחל")))
        assertEquals(2, nested.matches("סבתא רחל באה").size)
    }

    @Test
    fun `a doctor's title matches whether the caption writes it in full or abbreviated`() {
        assertEquals(1, hits("ד״ר כהן", "דוקטור כהן אמר"))
        assertEquals(1, hits("דוקטור כהן", "ד\"ר כהן אמר"))
        assertEquals(1, hits("דוקטור כהן", "אצל ד״ר כהן"))
        assertEquals(1, hits("ד״ר כהן", "תתקשרי לדוקטור כהן"))
        assertEquals(0, hits("ד״ר כהן", "דוקטור לוי אמר"))
        assertEquals(1, hits("דוקטור", "הדוקטור אמר"))
        assertEquals(1, hits("דוקטור", "תתקשרי לד״ר כהן"))
        assertEquals(0, hits("דוקטור", "הדר באה מחר"))
        assertEquals(0, hits("דוקטור כהן", "הדר כהן באה מחר"))
        assertEquals(0, hits("דוקטור", "גם והדר באה"))
        assertEquals(0, hits("ד״ר", "הדר באה מחר"))
    }
}

class KeywordAlertDeduplicatorTest {
    private val alert = KeywordAlert(phrase = "סבתא")
    private val matcher = KeywordAlertMatcher(listOf(alert))

    @Test
    fun `repeated partial updates for the same utterance fire a match only once`() {
        val deduplicator = KeywordAlertDeduplicator()
        val utteranceID = UUID.randomUUID()

        assertTrue(deduplicator.newMatches(utteranceID, matcher.matches("היום")).isEmpty())
        assertEquals(1, deduplicator.newMatches(utteranceID, matcher.matches("היום סבתא")).size)
        assertTrue(deduplicator.newMatches(utteranceID, matcher.matches("היום סבתא אכלה")).isEmpty())
    }

    @Test
    fun `a genuinely new occurrence later in the same utterance still fires`() {
        val deduplicator = KeywordAlertDeduplicator()
        val utteranceID = UUID.randomUUID()

        assertEquals(1, deduplicator.newMatches(utteranceID, matcher.matches("סבתא הגיעה")).size)

        val secondReported = deduplicator.newMatches(utteranceID, matcher.matches("סבתא הגיעה וגם סבתא התקשרה"))
        assertEquals(1, secondReported.size)
        assertEquals(3, secondReported[0].wordIndex)
    }

    @Test
    fun `a later pass that drops an earlier word moves the name, and it is still the same mention`() {
        val deduplicator = KeywordAlertDeduplicator()
        val utteranceID = UUID.randomUUID()

        assertEquals(1, deduplicator.newMatches(utteranceID, matcher.matches("אה בקיצור סבתא התקשרה")).size)
        assertTrue(deduplicator.newMatches(utteranceID, matcher.matches("אה סבתא התקשרה")).isEmpty())
        assertTrue(deduplicator.newMatches(utteranceID, matcher.matches("בקיצור אה סבתא התקשרה אתמול")).isEmpty())
    }

    @Test
    fun `a different utterance fires again for the same keyword`() {
        val deduplicator = KeywordAlertDeduplicator()

        val matches = matcher.matches("סבתא הגיעה")
        assertEquals(1, deduplicator.newMatches(UUID.randomUUID(), matches).size)
        assertEquals(1, deduplicator.newMatches(UUID.randomUUID(), matches).size)
    }

    @Test
    fun `forget resets an utterance so its matches can fire again`() {
        val deduplicator = KeywordAlertDeduplicator()
        val utteranceID = UUID.randomUUID()

        val matches = matcher.matches("סבתא הגיעה")
        assertEquals(1, deduplicator.newMatches(utteranceID, matches).size)

        deduplicator.forget(utteranceID)

        assertEquals(1, deduplicator.newMatches(utteranceID, matches).size)
    }

    @Test
    fun `forgetAll resets every utterance`() {
        val deduplicator = KeywordAlertDeduplicator()
        val firstUtterance = UUID.randomUUID()
        val secondUtterance = UUID.randomUUID()
        val matches = matcher.matches("סבתא הגיעה")

        deduplicator.newMatches(firstUtterance, matches)
        deduplicator.newMatches(secondUtterance, matches)
        deduplicator.forgetAll()

        assertEquals(1, deduplicator.newMatches(firstUtterance, matches).size)
        assertEquals(1, deduplicator.newMatches(secondUtterance, matches).size)
    }

    @Test
    fun `tracking is bounded to the 64 most recent utterances, evicting the oldest`() {
        val deduplicator = KeywordAlertDeduplicator()
        val matches = matcher.matches("סבתא הגיעה")

        val firstUtterance = UUID.randomUUID()
        assertEquals(1, deduplicator.newMatches(firstUtterance, matches).size)

        repeat(64) { deduplicator.newMatches(UUID.randomUUID(), matches) }

        assertEquals(1, deduplicator.newMatches(firstUtterance, matches).size)
    }
}

class KeywordAlertDecodingTest {
    @Test
    fun `a saved alert with no isEnabled key decodes as enabled`() {
        val json = """{"id":"${UUID.randomUUID().toString().uppercase()}","phrase":"סבתא"}"""
        val alert = KeywordAlert.fromJson(json)
        assertTrue(alert.isEnabled)
        assertEquals("סבתא", alert.phrase)
    }

    @Test
    fun `an alert survives a round trip through JSON`() {
        val alert = KeywordAlert(phrase = "סבתא", isEnabled = false)
        assertEquals(alert, KeywordAlert.fromJson(alert.toJson()))
    }

    @Test
    fun `hyphenated, maqaf-joined and spaced forms of a name match each other`() {
        val matcher = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "תל אביב")))
        assertEquals(1, matcher.matches("נסענו לתל-אביב אתמול").size)
        assertEquals(1, matcher.matches("נסענו לתל־אביב אתמול").size)
        val hyphenated = KeywordAlertMatcher(listOf(KeywordAlert(phrase = "בן-דוד")))
        assertEquals(1, hyphenated.matches("הגיע בן דוד שלי").size)
        assertEquals(HebrewText.normalize("תל אביב"), HebrewText.normalize("תל-אביב"))
    }
}

class HebrewTextStripNiqqudTest {
    @Test
    fun `niqqud vowel points and cantillation accents are stripped, but Hebrew punctuation in the same Unicode block is not`() {
        assertEquals("שלום", HebrewText.stripNiqqud("שָׁלוֹם"))
        assertEquals("תל־אביב", HebrewText.stripNiqqud("תל־אביב"))
        assertEquals("א׀ב", HebrewText.stripNiqqud("א׀ב"))
        assertEquals("א׃", HebrewText.stripNiqqud("א׃"))
        assertEquals("א׆ב", HebrewText.stripNiqqud("א׆ב"))
    }
}

class KeywordAttentionPolicyTest {
    @Test
    fun `her name gets her attention, then not again for every mention right after`() {
        val policy = KeywordAttentionPolicy(cooldownSeconds = 15.0)
        val name = UUID.randomUUID()
        assertTrue(policy.claimAttention(name, 100.0))
        assertFalse(policy.claimAttention(name, 104.0))
        assertFalse(policy.claimAttention(name, 114.0))
    }

    @Test
    fun `said again once the cooldown has passed, it gets her attention again, counted from the last time it did`() {
        val policy = KeywordAttentionPolicy(cooldownSeconds = 15.0)
        val name = UUID.randomUUID()
        assertTrue(policy.claimAttention(name, 100.0))
        assertFalse(policy.claimAttention(name, 110.0))
        assertTrue(policy.claimAttention(name, 115.0))
    }

    @Test
    fun `a clock set back an hour doesn't hold back the next time her name is said`() {
        val policy = KeywordAttentionPolicy(cooldownSeconds = 15.0)
        val name = UUID.randomUUID()
        assertTrue(policy.claimAttention(name, 10_000.0))
        assertTrue(policy.claimAttention(name, 10_000.0 - 3_600 + 30))
    }

    @Test
    fun `a different word is not held back by the first one`() {
        val policy = KeywordAttentionPolicy(cooldownSeconds = 15.0)
        assertTrue(policy.claimAttention(UUID.randomUUID(), 100.0))
        assertTrue(policy.claimAttention(UUID.randomUUID(), 101.0))
    }
}

class OtherLettersTest {
    @Test
    fun `a word with no Hebrew letters is flagged while the captions are Hebrew`() {
        assertTrue(HebrewText.isInOtherLetters("Sarah", "he"))
        assertTrue(HebrewText.isInOtherLetters("Саша", "he"))
        assertTrue(HebrewText.isInOtherLetters(" Dr. Cohen ", "he"))
        assertTrue(HebrewText.isInOtherLetters("سارة", "he"))
    }

    @Test
    fun `Hebrew letters anywhere, no letters at all, or captions in another language are not flagged`() {
        assertFalse(HebrewText.isInOtherLetters("שרה", "he"))
        assertFalse(HebrewText.isInOtherLetters("ד״ר Cohen", "he"))
        assertFalse(HebrewText.isInOtherLetters("שָׂרָה", "he"))
        assertFalse(HebrewText.isInOtherLetters("שׁרה", "he"))
        assertFalse(HebrewText.isInOtherLetters("", "he"))
        assertFalse(HebrewText.isInOtherLetters("   ", "he"))
        assertFalse(HebrewText.isInOtherLetters("112", "he"))
        assertFalse(HebrewText.isInOtherLetters("Sarah", "en"))
    }
}
