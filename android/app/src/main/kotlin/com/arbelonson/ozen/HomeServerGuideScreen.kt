package com.arbelonson.ozen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.WhisperModelCatalog
import com.arbelonson.ozen.core.tr

internal object HomeServerGuide {
    val steps: List<Pair<String, String>>
        get() = listOf(
            tr("מחשב מתאים", "A suitable computer") to tr("מחשב Windows בבית עם כרטיס מסך של NVIDIA, שיש לו לפחות 6 ג׳יגה זיכרון (8 ג׳יגה ומעלה לכתוביות מדויקות יותר). הוא צריך להישאר דלוק כשרוצים כתוביות ממנו.", "A Windows computer at home with an NVIDIA graphics card of at least 6 GB (8 GB or more for the more accurate captions). It has to be on when captions should come from it."),
            tr("להוריד במחשב את קובץ ההתקנה", "Download the setup file on the computer") to tr("שלחו לעצמכם את הקישור שלמטה (בדוא״ל או בוואטסאפ) ופתחו אותו במחשב. יורד קובץ אחד.", "Send yourself the link below (by email or WhatsApp) and open it on the computer. One file downloads."),
            tr("ללחוץ עליו פעמיים ולענות על השאלות", "Double-click it and answer its questions") to tr("הוא בודק קודם שכרטיס המסך מתאים, ומתקין הכול לבד (10 עד 30 דקות). אם Windows שואל אם לאפשר, עונים כן. הוא ישאל אם להשתמש במחשב גם מחוץ לבית.", "It first checks that the graphics card is good enough, then installs everything by itself (10 to 30 minutes). If Windows asks whether to allow it, say yes. It asks whether to use the computer away from home too."),
            tr("להקליד את הכתובת והקוד שהוא מציג", "Type in the address and code it shows") to tr(
                "בסוף מופיע במחשב ריבוע שחור־לבן. מתחתיו כתובת המחשב וקוד צימוד: מקלידים את שניהם בתיבות שתחת ״המחשב בבית״ בהגדרות. אפשר להציג אותו שוב מתפריט התחל, בשורה \u2068Ozen\u00A0-\u00A0pair\u00A0a\u00A0phone\u2069.",
                "At the end the computer shows a black-and-white square. Under it are the computer’s address and a pairing code: type both into the boxes under “Home computer” in Settings. To show it again: Start menu, “Ozen - pair a phone”.",
            ),
        )

    fun whenTheComputerIsOff(spoken: UILanguage): String =
        if (WhisperModelCatalog.phoneModelSource(spoken) == null) {
            tr("כשהמחשב כבוי או רחוק, הכתוביות מחכות לו וחוזרות לבד כשהוא עונה שוב.", "When the computer is off or out of reach, captions wait for it and start again by themselves once it answers.")
        } else {
            tr("כשהמחשב כבוי או רחוק, טלפון שהגיבוי כבר הורד אליו ממשיך לכתוב כתוביות לבד, וחוזר למחשב כשהוא עונה שוב. בלי הגיבוי, הכתוביות מחכות למחשב.", "When the computer is off or out of reach, a phone that already has the backup downloaded keeps writing captions by itself, and goes back to the computer once it answers again. Without the backup, captions wait for the computer.")
        }

    fun sendLink(context: Context) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, HomeServer.setupDownload.toString())
        context.startActivity(Intent.createChooser(send, null))
    }
}

@Composable
internal fun HomeServerGuideScreen(spoken: UILanguage, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    Screen(title = tr("הכנת המחשב בבית", "Setting up the home computer"), close = tr("חזרה", "Back"), onClose = onClose) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeServerGuide.steps.forEachIndexed { index, (title, text) ->
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text("${index + 1}. $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { HomeServerGuide.sendLink(context) }) { Text(tr("שליחת קישור ההורדה", "Send the download link")) }
            SelectionContainer {
                Text(HomeServer.setupDownload.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(tr("מחשב שנכנס למצב שינה לא עונה, כמו מחשב כבוי. ההתקנה מציעה למנוע ממנו להירדם מעצמו כשהוא מחובר לחשמל.", "A computer that has gone to sleep doesn’t answer, just like one that is off. Setup offers to stop it from falling asleep by itself while it is plugged in."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(HomeServerGuide.whenTheComputerIsOff(spoken), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
