import Foundation
import Testing
@testable import OzenKit

@Suite("Numbers standing out in captions")
struct NumberEmphasisTests {
    private func emphasized(_ text: String) -> [String] {
        NumberEmphasis.ranges(in: text).map { String(text[$0]) }
    }

    @Test("\"one\" or \"pa'am\" as the very last word of a line, even before a trailing space, is read safely")
    func lastWordOfTheLine() {
        #expect(emphasized("לקחתי את זה רק פעם") == [])
        #expect(emphasized("נשאר רק כדור אחד ") == ["אחד"])
        #expect(emphasized("נשארה רק אחת ") == ["אחת"])
    }

    @Test("times, phone numbers, fractions and percentages written in digits")
    func digits() {
        #expect(emphasized("ניפגש ב-10:30 אצל הרופא") == ["10:30"])
        #expect(emphasized("המספר הוא 050-1234567.") == ["050-1234567"])
        #expect(emphasized("הנחה של 20% על 3/4 מהמחיר") == ["20%", "3/4"])
        #expect(emphasized("בין 10:30-11:00, (בערך)") == ["10:30-11:00"])
    }

    @Test("a price in shekels joins the sign, glued or spaced; a thousands-grouped number stays whole")
    func shekelsAndGrouping() {
        #expect(emphasized("150₪") == ["150₪"])
        #expect(emphasized("מחיר הבדיקה 150 ₪") == ["150 ₪"])
        #expect(emphasized("המחיר הוא 1,234 שקל") == ["1,234 שקל"])
        #expect(emphasized("יש לי 1,234,567 שקל בבנק") == ["1,234,567 שקל"])
    }

    @Test("distinct numbers joined by a bare comma stand out separately, not as one merged number")
    func commaSeparatedDistinctNumbers() {
        #expect(emphasized("קח כדורים 1,2,3 ותנוח") == ["1", "2", "3"])
        #expect(emphasized("050-1234567,03-1234567") == ["050-1234567", "03-1234567"])
    }

    @Test("a comma glued after a word ends the word, so the amount right after it still stands out")
    func commaGluedAfterWord() {
        #expect(emphasized("כן,שלושה כדורים") == ["שלושה כדורים"])
        #expect(emphasized("בסך הכל,250 שקל") == ["250 שקל"])
    }

    @Test("numbers in words, with Hebrew's attached prefixes and around punctuation")
    func words() {
        #expect(emphasized("לקחת שלושה כדורים, ובשש בערב עוד חצי.") == ["שלושה כדורים", "ובשש", "חצי"])
        #expect(emphasized("שלושה-עשר אנשים") == ["שלושה-עשר"])
        #expect(emphasized("\u{200F}\"לשניים\"") == ["לשניים"])
    }

    @Test("a number word with vowel points stands out like the plain one, unit and all")
    func pointedWords() {
        #expect(emphasized("קחי שָׁלוֹשׁ כדורים") == ["שָׁלוֹשׁ כדורים"])
        #expect(emphasized("בְּשֵׁשׁ בערב") == ["בְּשֵׁשׁ"])
    }

    @Test("compound teens, tens-and-units, hundreds and time expressions chain into one span")
    func compoundNumbers() {
        #expect(emphasized("לקחת עשרים ושלושה כדורים") == ["עשרים ושלושה כדורים"])
        #expect(emphasized("חמש עשרה דקות") == ["חמש עשרה דקות"])
        #expect(emphasized("ניפגש בעשר וחצי") == ["בעשר וחצי"])
        #expect(emphasized("מאה ועשרים שקל") == ["מאה ועשרים שקל"])
        #expect(emphasized("אלפיים וחמש מאות שקל") == ["אלפיים וחמש מאות שקל"])
        // "ביום שני בשלוש" ("on Monday at three") is two unrelated times, not
        // a compound: a prefix other than "and" never chains.
        #expect(emphasized("ביום שני בשלוש") == ["שני", "בשלוש"])
    }

    @Test("the unit right after an amount stands out with it")
    func units() {
        #expect(emphasized("לקחת 3 כדורים ביום") == ["3 כדורים"])
        #expect(emphasized("חצי כדור בערב, ו-500 מ״ג בבוקר") == ["חצי כדור", "500 מ״ג"])
        #expect(emphasized("שלוש פעמים ביום") == ["שלוש פעמים"])
        // Punctuation after the number ends it; a unit before it isn't one.
        #expect(emphasized("בשעה 10:30, דקות ספורות") == ["10:30"])
        #expect(emphasized("פעם אחת ביום") == ["אחת"])
        #expect(NumberEmphasis.hasListableNumber("חצי כדור בערב"))
        #expect(NumberEmphasis.hasListableNumber("שני ימים"))
    }

    @Test("twice, two days, two weeks and the other words that are two by themselves stand out and are listed")
    func dualWords() {
        #expect(emphasized("פעמיים ביום, אחרי האוכל") == ["פעמיים"])
        #expect(emphasized("נתראה בעוד שבועיים, ולפני זה יומיים בלי") == ["שבועיים", "יומיים"])
        #expect(emphasized("תוך שעתיים, לחודשיים ובשנתיים האחרונות") == ["שעתיים", "לחודשיים", "ובשנתיים"])
        #expect(NumberEmphasis.hasListableNumber("כדור פעמיים ביום"))
        #expect(NumberEmphasis.hasListableNumber("ביקורת בעוד שבועיים"))
        #expect(emphasized("בדיקה שבועית וכדור יומי, תשלום חודשי") == [])
    }

    @Test("Latin dosage units, medication terms, temperatures and prices grandma hears from a doctor or a cashier keep their unit")
    func medicalAndCurrencyUnits() {
        #expect(emphasized("לקחת 500 mg בבוקר") == ["500 mg"])
        #expect(emphasized("לקחת 500 MG בבוקר") == ["500 MG"])
        #expect(emphasized("10 ml פעמיים ביום") == ["10 ml", "פעמיים"])
        #expect(emphasized("קח שתי טבליות ושתי קפסולות") == ["שתי טבליות", "ושתי קפסולות"])
        #expect(emphasized("שתי שאיפות מהמשאף") == ["שתי שאיפות"])
        #expect(emphasized("שלוש מנות ביום") == ["שלוש מנות"])
        #expect(emphasized("חום 38 מעלות") == ["38 מעלות"])
        #expect(emphasized("עולה 150 ש״ח") == ["150 ש״ח"])
        #expect(emphasized("זה עולה 20 דולר") == ["20 דולר"])
    }

    @Test("doses and measures written out in full, and three quarters, keep their unit")
    func moreUnits() {
        #expect(emphasized("שלושים מיליגרם בבוקר ועשר יחידות אינסולין") == ["שלושים מיליגרם", "ועשר יחידות"])
        #expect(emphasized("חמישה מיליליטר, שתי כפות ושלושת רבעי כוס") == ["חמישה מיליליטר", "שתי כפות", "ושלושת רבעי כוס"])
        #expect(emphasized("לחכות שלושים שניות") == ["שלושים שניות"])
    }

    @Test("quarters count only after a number, and a unit with the article still joins its amount")
    func quartersAndTheUnit() {
        #expect(emphasized("רבעי הירח משתנים כל שבוע") == [])
        #expect(NumberEmphasis.hasListableNumber("הדוח יוצא בכל רבעי השנה") == false)
        #expect(emphasized("שתיתי כבר שלושת רבעי הכוס") == ["שלושת רבעי הכוס"])
        #expect(emphasized("חצי הכוס, ורבע השעה הראשונה") == ["חצי הכוס", "ורבע השעה"])
        #expect(emphasized("שלושת רבעי, כוס") == ["שלושת רבעי"])
        #expect(emphasized("שלושת, רבעי כוס") == ["שלושת"])
    }

    @Test("once a day, a week or every few days stands out and is listed, like twice; never, again and once upon a time don't")
    func onceAPeriod() {
        #expect(emphasized("כדור פעם ביום, אחרי האוכל") == ["פעם"])
        #expect(emphasized("ביקורת פעם בשבוע ופעם בחודש בדיקת דם") == ["פעם", "ופעם"])
        #expect(emphasized("זריקה פעם בשלושה ימים") == ["פעם", "בשלושה ימים"])
        #expect(emphasized("אף פעם ביום כזה, עוד פעם בבוקר, פעם הייתי שם") == [])
        #expect(NumberEmphasis.hasListableNumber("כדור פעם ביום"))
        #expect(NumberEmphasis.hasListableNumber("פעם הייתי שם") == false)
    }

    @Test("today, this week and this year after an amount are not its unit; after a half or a quarter they are")
    func thisPeriodIsNotAUnit() {
        #expect(emphasized("לחץ הדם 120/80 היום") == ["120/80"])
        #expect(emphasized("קיבלתי שלושה השבוע ו-200 השנה") == ["שלושה", "200"])
        #expect(emphasized("חצי היום ורבע השנה") == ["חצי היום", "ורבע השנה"])
    }

    @Test("nobody, everybody and at once are not a count of one; once is")
    func notACount() {
        #expect(emphasized("אף אחד לא בא, כל אחד לבד, הכול בבת אחת") == [])
        #expect(emphasized("רק פעם אחת ביום") == ["אחת"])
    }

    @Test("one after a word that starts like 'that' is still a count: one sachet, one hour, one week")
    func oneAfterAShinWord() {
        #expect(emphasized("קחי שקית אחת בבוקר") == ["אחת"])
        #expect(emphasized("חכו שעה אחת אחרי האוכל") == ["אחת"])
        #expect(emphasized("התרופה למשך שבוע אחד") == ["אחד"])
    }

    @Test("everybody and nobody after 'that', one of the doctors, and on the one or the other hand are not counts")
    func idiomsFromRealSpeech() {
        // Lines the home computer wrote from broadcast and lecture speech.
        #expect(emphasized("אני מבקש שכל אחד מכם יקשיב") == [])
        #expect(emphasized("וכדי שלאף אחד לא יהיה ספק") == [])
        #expect(emphasized("היה לאחר ביקור אצל אחד הרופאים.") == [])
        #expect(emphasized("אחת הבעיות העיקריות של החילונים") == [])
        #expect(emphasized("הוא הפך אותה לאחת הקלפטוקרטיות המושחתות") == [])
        #expect(emphasized("מצד אחד להסתכל על זה, ומצד שני, זה לא רע.") == [])
        // Still counts and times.
        #expect(emphasized("שכל שלושה ילדים יקבלו כדור אחד") == ["שלושה", "אחד"])
        #expect(emphasized("באחת בלילה ההר הפסיק לרקוד.") == ["באחת"])
        #expect(emphasized("אחד, הרופאים אמרו, זה מספיק") == ["אחד"])
        #expect(emphasized("בשעה אחת הילדים יוצאים") == ["אחת"])
        #expect(emphasized("קיבלתי שני כדורים בצד אחד") == ["שני כדורים", "אחד"])
    }

    @Test("each other, the other one and the second are not counts; Monday and the two of them are")
    func otherOne() {
        #expect(emphasized("הם עוזרים אחד לשני, אחת את השנייה") == [])
        #expect(emphasized("בצד השני, והשני לא בא") == [])
        #expect(emphasized("ביום שני בשלוש, השניים באו עם שני ילדים") == ["שני", "בשלוש", "השניים", "שני"])
        #expect(emphasized("נתראה בשני, זה מספיק לשני אנשים") == ["בשני", "לשני"])
    }

    @Test("words that only contain a number word, or years and weeks, stay plain")
    func lookalikes() {
        #expect(emphasized("לפני שנים, בעוד שבוע, המונה") == [])
        #expect(emphasized("") == [])
        #expect(emphasized("   ") == [])
    }

    @Test("a prefix counts only in front of a whole number word")
    func shortWords() {
        #expect(emphasized("שש משש") == ["שש", "משש"])
        #expect(emphasized("מש הש") == [])
    }

    @Test("lines listed under numbers said: digits and real amounts, not the idioms of one and two")
    func listable() {
        #expect(NumberEmphasis.hasListableNumber("ניפגש ב-10:30"))
        #expect(NumberEmphasis.hasListableNumber("לקחת שלושה כדורים"))
        #expect(NumberEmphasis.hasListableNumber("ובערב חצי כדור"))
        #expect(NumberEmphasis.hasListableNumber("רק פעם אחת") == false)
        #expect(NumberEmphasis.hasListableNumber("נתראה ביום שני") == false)
        #expect(NumberEmphasis.hasListableNumber("כל אחד לבד") == false)
        #expect(NumberEmphasis.hasListableNumber("") == false)
    }

    @Test("one or two as part of a time or an amount is listed: eleven, half past one, two pills")
    func listableOnesAndTwos() {
        #expect(NumberEmphasis.hasListableNumber("נתראה באחת עשרה בלילה"))
        #expect(NumberEmphasis.hasListableNumber("התור באחת וחצי בצהריים"))
        #expect(NumberEmphasis.hasListableNumber("לקחת שני כדורים"))
        #expect(NumberEmphasis.hasListableNumber("עד אחת-עשרה בלילה"))
        #expect(NumberEmphasis.hasListableNumber("נתראה ביום שני בבוקר") == false)
        #expect(NumberEmphasis.hasListableNumber("אחת ולתמיד, רק פעם אחת") == false)
    }

    @Test("on by default, survives older settings files, and can be turned off")
    func settingDefault() throws {
        #expect(DisplayPreferences.default.emphasizeNumbers)
        let old = try JSONDecoder().decode(DisplayPreferences.self, from: Data(#"{"fontSize":30}"#.utf8))
        #expect(old.emphasizeNumbers)
        var off = DisplayPreferences.default
        off.emphasizeNumbers = false
        let roundTripped = try JSONDecoder().decode(DisplayPreferences.self, from: JSONEncoder().encode(off))
        #expect(roundTripped.emphasizeNumbers == false)
    }
}
