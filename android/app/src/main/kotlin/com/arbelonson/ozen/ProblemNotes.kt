package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr
import java.time.ZoneId

object ProblemNotes {
    fun marked(keptSound: Boolean, keptCaptions: Boolean): String = when {
        keptCaptions && keptSound -> tr("פרטי התקלה, השורות האחרונות ו־30 השניות האחרונות של הקול נשמרו בטלפון בלבד. הכפתור שולח את הפרטים והשורות; את הקול שולחים מהגדרות ← אבחון.", "The problem’s details, the last few lines and the last 30 seconds of sound were saved, on the phone only. The button sends the details and lines; the sound is sent from Settings → Diagnostics.")
        keptCaptions -> tr("פרטי התקלה והשורות האחרונות נשמרו בטלפון בלבד.", "The problem’s details and the last few lines were saved, on the phone only.")
        else -> tr("פרטי התקלה נשמרו בטלפון בלבד, בלי שורות כתוביות ובלי קול, כי שמירת השיחות כבויה.", "The problem’s details were saved, on the phone only, with no caption lines and no sound because saving conversations is off.")
    }

    fun reportLine(markedAt: Double, zone: ZoneId = ZoneId.systemDefault()): String =
        tr("אוזן בטלפון הזה: סומנה בעיה בכתוביות בשעה %1.", "Ozen on this phone: a captions problem was marked at %1.", listOf(clockTime(markedAt, zone)))

    fun diagnostics(keptCaptions: Boolean): String = if (keptCaptions) tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח. כשמסמנים בעיה נשמרות גם השורות האחרונות של הכתוביות.", "Kept on the phone even when the app closes, and sent only with the report. Marking a problem also keeps the last few caption lines.") else tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח.", "Kept on the phone even when the app closes, and sent only with the report.")
}
