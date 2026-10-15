package com.arbelonson.ozen

import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr

internal object SoundAlertsText {
    fun safetyNote(language: UILanguage): String = tr(
        "אוזן היא כלי עזר, לא אזעקה מאושרת. אין לסמוך עליה במצבים שבהם אפשר להיפגע או בשעת חירום: השאירו את גלאי העשן במקומם, ואת ההתרעות של פיקוד העורף פעילות.",
        "Ozen is a helper, not a certified alarm. Don’t rely on it where you could be hurt or in an emergency: keep your smoke alarms, and keep official emergency alerts turned on.",
        language,
    )
}
