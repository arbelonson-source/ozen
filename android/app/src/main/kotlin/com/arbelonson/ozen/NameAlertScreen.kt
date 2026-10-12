package com.arbelonson.ozen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.tr

@Composable
internal fun NameAlertScreen(settings: AppSettings, onAddName: (String) -> Unit, onAddAlert: (String) -> Unit, onClose: () -> Unit) {
    val draft = rememberSaveable { mutableStateOf("") }
    val addDraft = {
        if (draft.value.isNotBlank()) onAddName(draft.value.trim())
        draft.value = ""
    }
    val close = {
        addDraft()
        onClose()
    }
    BackHandler(onBack = close)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScreenTitle(tr("כשקוראים לך", "When your name is called")) {
                TextButton(onClick = close) { Text(tr("סיום", "Done")) }
            }
            Text(
                tr("כשמישהו אומר את השם שלך, הטלפון רוטט והשורה מסומנת, גם כשלא מסתכלים על המסך.", "When someone says your name, the phone vibrates and the line is marked, even when you’re not looking at the screen."),
                style = MaterialTheme.typography.titleLarge,
            )
            NameAlertForm(settings, draft, addDraft, onAddAlert)
            Text(
                tr("אפשר להוסיף עוד מילים, או למחוק, בהגדרות ← התראות ← מילים חשובות.", "You can add more words, or delete them, in Settings → Alerts → Important words."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
