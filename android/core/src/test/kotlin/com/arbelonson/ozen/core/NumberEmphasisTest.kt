package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumberEmphasisTest {
    private fun emphasized(text: String): List<String> =
        NumberEmphasis.ranges(text).map { text.substring(it.start, it.endExclusive) }

    @Test
    fun `"one" or "pa'am" as the very last word of a line, even before a trailing space, is read safely`() {
        assertEquals(emptyList(), emphasized("לקחתי את זה רק פעם"))
        assertEquals(listOf("אחד"), emphasized("נשאר רק כדור אחד "))
        assertEquals(listOf("אחת"), emphasized("נשארה רק אחת "))
    }

    @Test
    fun `times, phone numbers, fractions and percentages written in digits`() {
        assertEquals(listOf("10:30"), emphasized("ניפגש ב-10:30 אצל הרופא"))
        assertEquals(listOf("050-1234567"), emphasized("המספר הוא 050-1234567."))
        assertEquals(listOf("20%", "3/4"), emphasized("הנחה של 20% על 3/4 מהמחיר"))
        assertEquals(listOf("10:30-11:00"), emphasized("בין 10:30-11:00, (בערך)"))
    }

    @Test
    fun `a price in shekels joins the sign, glued or spaced, a thousands-grouped number stays whole`() {
        assertEquals(listOf("150₪"), emphasized("150₪"))
        assertEquals(listOf("150 ₪"), emphasized("מחיר הבדיקה 150 ₪"))
        assertEquals(listOf("1,234 שקל"), emphasized("המחיר הוא 1,234 שקל"))
        assertEquals(listOf("1,234,567 שקל"), emphasized("יש לי 1,234,567 שקל בבנק"))
    }

    @Test
    fun `distinct numbers joined by a bare comma stand out separately, not as one merged number`() {
        assertEquals(listOf("1", "2", "3"), emphasized("קח כדורים 1,2,3 ותנוח"))
        assertEquals(listOf("050-1234567", "03-1234567"), emphasized("050-1234567,03-1234567"))
    }

    @Test
    fun `"today" after a unit or the shekel sign is not part of the amount, "the day" after a fraction is`() {
        assertEquals(listOf("3 כדורים"), emphasized("3 כדורים היום"))
        assertEquals(listOf("150 ₪"), emphasized("שילמתי 150 ₪ היום"))
        assertEquals(listOf("שלושת רבעי היום"), emphasized("שלושת רבעי היום"))
    }

    @Test
    fun `a comma glued after a word ends the word, so the amount right after it still stands out`() {
        assertEquals(listOf("שלושה כדורים"), emphasized("כן,שלושה כדורים"))
        assertEquals(listOf("250 שקל"), emphasized("בסך הכל,250 שקל"))
    }

    @Test
    fun `numbers in words, with Hebrew's attached prefixes and around punctuation`() {
        assertEquals(listOf("שלושה כדורים", "ובשש", "חצי"), emphasized("לקחת שלושה כדורים, ובשש בערב עוד חצי."))
        assertEquals(listOf("שלושה-עשר"), emphasized("שלושה-עשר אנשים"))
        assertEquals(listOf("לשניים"), emphasized("‏\"לשניים\""))
    }

    @Test
    fun `a number word with vowel points stands out like the plain one, unit and all`() {
        assertEquals(listOf("שָׁלוֹשׁ כדורים"), emphasized("קחי שָׁלוֹשׁ כדורים"))
        assertEquals(listOf("בְּשֵׁשׁ"), emphasized("בְּשֵׁשׁ בערב"))
    }

    @Test
    fun `compound teens, tens-and-units, hundreds and time expressions chain into one span`() {
        assertEquals(listOf("עשרים ושלושה כדורים"), emphasized("לקחת עשרים ושלושה כדורים"))
        assertEquals(listOf("חמש עשרה דקות"), emphasized("חמש עשרה דקות"))
        assertEquals(listOf("בעשר וחצי"), emphasized("ניפגש בעשר וחצי"))
        assertEquals(listOf("מאה ועשרים שקל"), emphasized("מאה ועשרים שקל"))
        assertEquals(listOf("אלפיים וחמש מאות שקל"), emphasized("אלפיים וחמש מאות שקל"))
        assertEquals(listOf("שני", "בשלוש"), emphasized("ביום שני בשלוש"))
    }

    @Test
    fun `the unit right after an amount stands out with it`() {
        assertEquals(listOf("3 כדורים"), emphasized("לקחת 3 כדורים ביום"))
        assertEquals(listOf("חצי כדור", "500 מ״ג"), emphasized("חצי כדור בערב, ו-500 מ״ג בבוקר"))
        assertEquals(listOf("שלוש פעמים"), emphasized("שלוש פעמים ביום"))
        assertEquals(listOf("10:30"), emphasized("בשעה 10:30, דקות ספורות"))
        assertEquals(listOf("אחת"), emphasized("פעם אחת ביום"))
        assertTrue(NumberEmphasis.hasListableNumber("חצי כדור בערב"))
        assertTrue(NumberEmphasis.hasListableNumber("שני ימים"))
    }

    @Test
    fun `twice, two days, two weeks and the other words that are two by themselves stand out and are listed`() {
        assertEquals(listOf("פעמיים"), emphasized("פעמיים ביום, אחרי האוכל"))
        assertEquals(listOf("שבועיים", "יומיים"), emphasized("נתראה בעוד שבועיים, ולפני זה יומיים בלי"))
        assertEquals(listOf("שעתיים", "לחודשיים", "ובשנתיים"), emphasized("תוך שעתיים, לחודשיים ובשנתיים האחרונות"))
        assertTrue(NumberEmphasis.hasListableNumber("כדור פעמיים ביום"))
        assertTrue(NumberEmphasis.hasListableNumber("ביקורת בעוד שבועיים"))
        assertEquals(emptyList(), emphasized("בדיקה שבועית וכדור יומי, תשלום חודשי"))
    }

    @Test
    fun `Latin dosage units, medication terms, temperatures and prices grandma hears from a doctor or a cashier keep their unit`() {
        assertEquals(listOf("500 mg"), emphasized("לקחת 500 mg בבוקר"))
        assertEquals(listOf("500 MG"), emphasized("לקחת 500 MG בבוקר"))
        assertEquals(listOf("10 ml", "פעמיים"), emphasized("10 ml פעמיים ביום"))
        assertEquals(listOf("שתי טבליות", "ושתי קפסולות"), emphasized("קח שתי טבליות ושתי קפסולות"))
        assertEquals(listOf("שתי שאיפות"), emphasized("שתי שאיפות מהמשאף"))
        assertEquals(listOf("שלוש מנות"), emphasized("שלוש מנות ביום"))
        assertEquals(listOf("38 מעלות"), emphasized("חום 38 מעלות"))
        assertEquals(listOf("150 ש״ח"), emphasized("עולה 150 ש״ח"))
        assertEquals(listOf("20 דולר"), emphasized("זה עולה 20 דולר"))
    }

    @Test
    fun `doses and measures written out in full, and three quarters, keep their unit`() {
        assertEquals(listOf("שלושים מיליגרם", "ועשר יחידות"), emphasized("שלושים מיליגרם בבוקר ועשר יחידות אינסולין"))
        assertEquals(
            listOf("חמישה מיליליטר", "שתי כפות", "ושלושת רבעי כוס"),
            emphasized("חמישה מיליליטר, שתי כפות ושלושת רבעי כוס"),
        )
        assertEquals(listOf("שלושים שניות"), emphasized("לחכות שלושים שניות"))
    }

    @Test
    fun `quarters count only after a number, and a unit with the article still joins its amount`() {
        assertEquals(emptyList(), emphasized("רבעי הירח משתנים כל שבוע"))
        assertFalse(NumberEmphasis.hasListableNumber("הדוח יוצא בכל רבעי השנה"))
        assertEquals(listOf("שלושת רבעי הכוס"), emphasized("שתיתי כבר שלושת רבעי הכוס"))
        assertEquals(listOf("חצי הכוס", "ורבע השעה"), emphasized("חצי הכוס, ורבע השעה הראשונה"))
        assertEquals(listOf("חצי הכף"), emphasized("ממיסים חצי הכף במים"))
        assertEquals(listOf("שלושת רבעי"), emphasized("שלושת רבעי, כוס"))
        assertEquals(listOf("שלושת"), emphasized("שלושת, רבעי כוס"))
    }

    @Test
    fun `once a day, a week or every few days stands out and is listed, like twice, never, again and once upon a time don't`() {
        assertEquals(listOf("פעם"), emphasized("כדור פעם ביום, אחרי האוכל"))
        assertEquals(listOf("פעם", "ופעם"), emphasized("ביקורת פעם בשבוע ופעם בחודש בדיקת דם"))
        assertEquals(listOf("פעם", "בשלושה ימים"), emphasized("זריקה פעם בשלושה ימים"))
        assertEquals(listOf("פעם", "בשש שעות"), emphasized("כדור פעם בשש שעות"))
        assertEquals(emptyList(), emphasized("אף פעם ביום כזה, עוד פעם בבוקר, פעם הייתי שם"))
        assertTrue(NumberEmphasis.hasListableNumber("כדור פעם ביום"))
        assertFalse(NumberEmphasis.hasListableNumber("פעם הייתי שם"))
    }

    @Test
    fun `today, this week and this year after an amount are not its unit, after a half or a quarter they are`() {
        assertEquals(listOf("120/80"), emphasized("לחץ הדם 120/80 היום"))
        assertEquals(listOf("שלושה", "200"), emphasized("קיבלתי שלושה השבוע ו-200 השנה"))
        assertEquals(listOf("חצי היום", "ורבע השנה"), emphasized("חצי היום ורבע השנה"))
    }

    @Test
    fun `nobody, everybody and at once are not a count of one, once is`() {
        assertEquals(emptyList(), emphasized("אף אחד לא בא, כל אחד לבד, הכול בבת אחת"))
        assertEquals(listOf("אחת"), emphasized("רק פעם אחת ביום"))
    }

    @Test
    fun `one after a word that starts like 'that' is still a count, one sachet, one hour, one week`() {
        assertEquals(listOf("אחת"), emphasized("קחי שקית אחת בבוקר"))
        assertEquals(listOf("אחת"), emphasized("חכו שעה אחת אחרי האוכל"))
        assertEquals(listOf("אחד"), emphasized("התרופה למשך שבוע אחד"))
    }

    @Test
    fun `everybody and nobody after 'that', one of the doctors, and on the one or the other hand are not counts`() {
        assertEquals(emptyList(), emphasized("אני מבקש שכל אחד מכם יקשיב"))
        assertEquals(emptyList(), emphasized("וכדי שלאף אחד לא יהיה ספק"))
        assertEquals(emptyList(), emphasized("היה לאחר ביקור אצל אחד הרופאים."))
        assertEquals(emptyList(), emphasized("אחד הבנים יבוא מחר"))
        assertEquals(emptyList(), emphasized("אחת הבעיות העיקריות של החילונים"))
        assertEquals(emptyList(), emphasized("הוא הפך אותה לאחת הקלפטוקרטיות המושחתות"))
        assertEquals(emptyList(), emphasized("מצד אחד להסתכל על זה, ומצד שני, זה לא רע."))
        assertEquals(listOf("שלושה", "אחד"), emphasized("שכל שלושה ילדים יקבלו כדור אחד"))
        assertEquals(listOf("באחת"), emphasized("באחת בלילה ההר הפסיק לרקוד."))
        assertEquals(listOf("אחד"), emphasized("אחד, הרופאים אמרו, זה מספיק"))
        assertEquals(listOf("אחת"), emphasized("בשעה אחת הילדים יוצאים"))
        assertEquals(listOf("שני כדורים", "אחד"), emphasized("קיבלתי שני כדורים בצד אחד"))
    }

    @Test
    fun `each other, the other one and the second are not counts, Monday and the two of them are`() {
        assertEquals(emptyList(), emphasized("הם עוזרים אחד לשני, אחת את השנייה"))
        assertEquals(emptyList(), emphasized("בצד השני, והשני לא בא"))
        assertEquals(listOf("שני", "בשלוש", "השניים", "שני"), emphasized("ביום שני בשלוש, השניים באו עם שני ילדים"))
        assertEquals(listOf("בשני", "לשני"), emphasized("נתראה בשני, זה מספיק לשני אנשים"))
    }

    @Test
    fun `words that only contain a number word, or years and weeks, stay plain`() {
        assertEquals(emptyList(), emphasized("לפני שנים, בעוד שבוע, המונה"))
        assertEquals(emptyList(), emphasized(""))
        assertEquals(emptyList(), emphasized("   "))
    }

    @Test
    fun `a prefix counts only in front of a whole number word`() {
        assertEquals(listOf("שש", "משש"), emphasized("שש משש"))
        assertEquals(emptyList(), emphasized("מש הש"))
    }

    @Test
    fun `lines listed under numbers said, digits and real amounts, not the idioms of one and two`() {
        assertTrue(NumberEmphasis.hasListableNumber("ניפגש ב-10:30"))
        assertTrue(NumberEmphasis.hasListableNumber("לקחת שלושה כדורים"))
        assertTrue(NumberEmphasis.hasListableNumber("ובערב חצי כדור"))
        assertFalse(NumberEmphasis.hasListableNumber("רק פעם אחת"))
        assertFalse(NumberEmphasis.hasListableNumber("נתראה ביום שני"))
        assertFalse(NumberEmphasis.hasListableNumber("כל אחד לבד"))
        assertFalse(NumberEmphasis.hasListableNumber(""))
    }

    @Test
    fun `one or two as part of a time or an amount is listed, eleven, half past one, two pills`() {
        assertTrue(NumberEmphasis.hasListableNumber("נתראה באחת עשרה בלילה"))
        assertTrue(NumberEmphasis.hasListableNumber("התור באחת וחצי בצהריים"))
        assertTrue(NumberEmphasis.hasListableNumber("לקחת שני כדורים"))
        assertTrue(NumberEmphasis.hasListableNumber("עד אחת-עשרה בלילה"))
        assertFalse(NumberEmphasis.hasListableNumber("נתראה ביום שני בבוקר"))
        assertFalse(NumberEmphasis.hasListableNumber("אחת ולתמיד, רק פעם אחת"))
    }
}
