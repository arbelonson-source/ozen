package com.arbelonson.ozen

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.DonationAddress
import com.arbelonson.ozen.core.SupportOzen
import com.arbelonson.ozen.core.tr
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

/**
 * Ways to give to Ozen, from the end of Settings, as on the iPhone: never
 * asked for, never in the way. Each address comes with a code to scan
 * with a wallet on another phone and a button to copy it, and the steps
 * say to pick the same coin, since a coin sent to another coin's address
 * is lost.
 */
@Composable
internal fun SupportScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("תמיכה באוזן", "Support Ozen"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text(tr("חזרה", "Back")) }
            }
            Text(
                tr(
                    "אוזן חינמית ותישאר חינמית, בלי פרסומות ובלי שום דבר נעול. תרומה היא לגמרי לפי רצונכם: אל תרגישו שום מחויבות, ותרמו רק אם המצב הכלכלי שלכם מאפשר זאת בנוחות. עצם השימוש באוזן והמלצה עליה לאחרים כבר עוזרים מאוד.",
                    "Ozen is free and will stay free, with no ads and nothing locked. Donating is entirely up to you: please never feel obligated, and only donate if you’re in a financial position where you can comfortably afford it. Using Ozen and telling others about it already helps a lot.",
                ),
            )
            Heading(tr("איך תורמים", "How to donate"))
            Text(tr("1. פתחו את אפליקציית ארנק הקריפטו שלכם ובחרו ״שליחה״.", "1. Open your crypto wallet app and choose Send."))
            Text(tr("2. בחרו את אותו מטבע שמופיע כאן. מטבע שנשלח לכתובת של מטבע אחר הולך לאיבוד.", "2. Pick the same coin as shown here. A coin sent to another coin’s address is lost."))
            Text(tr("3. סרקו את הקוד עם הארנק, או העתיקו את הכתובת והדביקו אותה.", "3. Scan the code with the wallet, or copy the address and paste it."))
            Text(tr("4. לפני השליחה, ודאו שהתווים הראשונים והאחרונים של הכתובת זהים.", "4. Before sending, check that the first and last few characters of the address match."))
            for (donation in SupportOzen.addresses) DonationRow(donation)
        }
    }
}

@Composable
private fun DonationRow(donation: DonationAddress) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Heading(donation.coin)
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val code = remember(donation.id) { qrCode(donation.paymentURI) }
        Image(
            bitmap = code,
            contentDescription = null,
            filterQuality = FilterQuality.None,
            modifier = Modifier.testTag("qr:${donation.id}").background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp).size(196.dp),
        )
        // An address reads left to right in a Hebrew screen too, and a
        // screen reader spells it out four characters at a time.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                donation.address,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spokenAddress(donation.address) },
            )
        }
        OutlinedButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(donation.coin, donation.address))
                copied = true
            },
        ) { Text(if (copied) tr("הועתק", "Copied") else tr("העתקת כתובת ה־%1", "Copy the %1 address", listOf(donation.coin))) }
    }
    if (donation == SupportOzen.ethereum) {
        Text(
            tr("רשת את׳ריום בלבד. אפשר לשלוח גם USDC ו־USDT ברשת את׳ריום.", "Ethereum network only. USDC and USDT on Ethereum are welcome too."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun spokenAddress(address: String): String =
    address.chunked(4).joinToString(", ") { it.toList().joinToString(" ") }

/** A QR code with one pixel per module; the white around it is the quiet zone a reader needs. */
internal fun qrCode(text: String): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { at ->
        if (matrix[at % matrix.width, at / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
