package com.arbelonson.ozen

import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.tr

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phoneLanguages = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }
        Localization.language = AppLanguage.System.resolved(phoneLanguages)
        enableEdgeToEdge()
        setContent { OzenApp() }
    }
}

@Composable
private fun OzenApp() {
    val direction = if (Localization.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            CaptionScreen()
        }
    }
}

@Composable
private fun CaptionScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = tr("הכתוביות יופיעו כאן.", "Captions will appear here."),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 32.sp,
                lineHeight = 40.sp,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = tr("הכתוביות כבויות", "Captions are off"),
            color = Color.White,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
