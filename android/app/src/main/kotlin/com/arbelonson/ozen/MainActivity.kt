package com.arbelonson.ozen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.tr

class MainActivity : ComponentActivity() {
    private val askForMicrophone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            ListeningService.start(this)
        } else {
            CaptionState.setPhase(ListeningPhase.NoMicrophone)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = AppLanguage.System.resolved(phoneLanguages)
        enableEdgeToEdge()
        val pairing = (application as OzenApplication).pairing
        setContent { OzenApp(pairing, onStatusTap = ::statusTapped) }
        openLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let { (application as OzenApplication).pairing.open(it) }
    }

    private fun statusTapped() {
        when (CaptionState.screen.value.phase) {
            ListeningPhase.Listening -> ListeningService.pause(this)
            ListeningPhase.NoMicrophone -> if (hasMicrophone()) startListening() else openAppSettings()
            ListeningPhase.Off, ListeningPhase.Paused -> startListening()
        }
    }

    private fun startListening() {
        if (hasMicrophone()) {
            ListeningService.start(this)
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        askForMicrophone.launch(wanted.toTypedArray())
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

@Composable
private fun OzenApp(pairing: PairingRequests, onStatusTap: () -> Unit) {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    val state by CaptionState.screen.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            CaptionScreen(state, onStatusTap)
            PairingDialogs(pairing)
        }
    }
}

@Composable
private fun PairingDialogs(pairing: PairingRequests) {
    val pending by pairing.pending.collectAsStateWithLifecycle()
    val saveFailed by pairing.saveFailed.collectAsStateWithLifecycle()
    val linkBroken by pairing.linkBroken.collectAsStateWithLifecycle()
    pending?.let { shown ->
        AlertDialog(
            onDismissRequest = { pairing.pending.value = null },
            title = { Text(tr("להתחבר למחשב בבית?", "Connect to the home computer?")) },
            text = {
                Text(
                    tr(
                        "הקול ישלח לכתוביות אל %1. אשרו רק אם זה המחשב של המשפחה.",
                        "The audio will go to %1 for captions. Only connect if this is the family’s computer.",
                        listOf(shown.computerName),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.accept(shown) }) { Text(tr("להתחבר", "Connect")) } },
            dismissButton = { TextButton(onClick = { pairing.pending.value = null }) { Text(tr("ביטול", "Cancel")) } },
        )
    }
    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { pairing.saveFailed.value = false },
            title = { Text(tr("החיבור למחשב בבית לא נשמר", "The connection to the home computer wasn’t saved")) },
            text = {
                Text(
                    tr(
                        "הכתוביות ממשיכות כמו קודם. סרקו שוב את הקוד, או בקשו עזרה ממי שהתקין את הטלפון.",
                        "Captions carry on as before. Scan the code again, or ask whoever set up the phone for help.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.saveFailed.value = false }) { Text(tr("סגירה", "Close")) } },
        )
    }
    if (linkBroken) {
        AlertDialog(
            onDismissRequest = { pairing.linkBroken.value = false },
            title = { Text(tr("הקישור למחשב בבית לא תקין", "The home computer link didn’t come through")) },
            text = {
                Text(
                    tr(
                        "חלק מהקישור חסר או השתבש. סרקו שוב את הריבוע במחשב עם מצלמת הטלפון, ממש מקרוב.",
                        "Part of the link is missing or garbled. Scan the square on the computer again with the phone’s camera, up close.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { pairing.linkBroken.value = false }) { Text(tr("סגירה", "Close")) } },
        )
    }
}

@Composable
private fun CaptionScreen(state: CaptionScreenState, onStatusTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.lines.isEmpty()) {
                Text(
                    text = tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                CaptionLines(state.lines)
            }
        }
        StatusButton(state.phase, onStatusTap)
    }
}

@Composable
private fun CaptionLines(lines: List<CaptionLine>) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) { list.scrollToItem(lines.size - 1) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines, key = { it.id }) { line ->
            Text(
                text = line.text,
                color = if (line.isFinal) Color.White else Color.White.copy(alpha = 0.75f),
                fontSize = 34.sp,
                lineHeight = 42.sp,
            )
        }
    }
}

@Composable
private fun StatusButton(phase: ListeningPhase, onTap: () -> Unit) {
    val (title, hint) = when (phase) {
        ListeningPhase.Off -> tr("הכתוביות כבויות", "Captions are off") to tr("הקישו כדי להתחיל", "Tap to start")
        ListeningPhase.Listening -> tr("מקשיב", "Listening") to tr("הקישו כדי להשהות", "Tap to pause")
        ListeningPhase.Paused -> tr("מושהה", "Paused") to tr("הקישו כדי להמשיך", "Tap to continue")
        ListeningPhase.NoMicrophone -> tr("אין גישה למיקרופון", "No microphone access") to
            tr("פתיחת הגדרות הטלפון", "Open phone settings")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .clickable(onClick = onTap)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
        Text(text = hint, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}
