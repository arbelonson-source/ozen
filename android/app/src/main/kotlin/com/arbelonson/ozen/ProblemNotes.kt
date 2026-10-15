package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr

object ProblemNotes {
    fun marked(keptSound: Boolean, keptCaptions: Boolean): String = when {
        keptCaptions && keptSound -> tr("פרטי התקלה, השורות האחרונות ו-30 השניות האחרונות של הקול נשמרו בטלפון בלבד. כדי לשלוח אותם למי שעוזר לך: הגדרות ← אבחון.", "The problem's details, the last few lines and the last 30 seconds of sound were saved, on the phone only. To send them to whoever helps you: Settings → Diagnostics.")
        keptCaptions -> tr("פרטי התקלה והשורות האחרונות נשמרו בטלפון בלבד. כדי לשלוח אותם למי שעוזר לך: הגדרות ← אבחון ← שליחת הדוח.", "The problem's details and the last few lines were saved, on the phone only. To send them to whoever helps you: Settings → Diagnostics → Send report.")
        else -> tr("פרטי התקלה נשמרו בטלפון בלבד, בלי שורות כתוביות ובלי קול, כי שמירת השיחות כבויה. כדי לשלוח אותם למי שעוזר לך: הגדרות ← אבחון ← שליחת הדוח.", "The problem's details were saved, on the phone only, with no caption lines and no sound because saving conversations is off. To send them to whoever helps you: Settings → Diagnostics → Send report.")
    }

    fun diagnostics(keptCaptions: Boolean): String = if (keptCaptions) tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח. כשמסמנים בעיה נשמרות גם השורות האחרונות של הכתוביות.", "Kept on the phone even when the app closes, and sent only with the report. Marking a problem also keeps the last few caption lines.") else tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח.", "Kept on the phone even when the app closes, and sent only with the report.")
}
