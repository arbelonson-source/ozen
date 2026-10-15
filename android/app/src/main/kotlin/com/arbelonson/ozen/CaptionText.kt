package com.arbelonson.ozen

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.arbelonson.ozen.core.NumberEmphasis
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.UncertainWords

val NUMBER_YELLOW = Color(red = 1.0f, green = 0.88f, blue = 0.2f)

fun usesDecimalComma(language: UILanguage): Boolean = when (language) {
    UILanguage.French, UILanguage.Spanish, UILanguage.German, UILanguage.Portuguese,
    UILanguage.Russian, UILanguage.Ukrainian -> true
    else -> false
}

fun usesDecimalComma(languageCode: String): Boolean = usesDecimalComma(UILanguage.match(languageCode) ?: UILanguage.Hebrew)

val LocalDecimalComma = staticCompositionLocalOf { false }

const val DOUBTFUL_WORD = "doubtful"

fun captionText(
    shown: String,
    emphasizeNumbers: Boolean,
    numberColor: Color?,
    doubtfulWords: List<String> = emptyList(),
    linkingPhoneNumbers: Boolean = false,
    decimalComma: Boolean = false,
): AnnotatedString {
    val ranges = if (emphasizeNumbers) NumberEmphasis.ranges(shown, decimalComma) else emptyList()
    val doubted = if (doubtfulWords.isEmpty()) emptyList() else UncertainWords.ranges(shown, doubtfulWords)
    val phones = if (linkingPhoneNumbers) PhoneNumbers.matches(shown) else emptyList()
    if (ranges.isEmpty() && doubted.isEmpty() && phones.isEmpty()) return AnnotatedString(shown)
    return buildAnnotatedString {
        append(shown)
        for (range in ranges) {
            addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = numberColor ?: Color.Unspecified), range.start, range.endExclusive)
        }
        for (range in doubted) {
            addStringAnnotation(DOUBTFUL_WORD, "", range.start, range.endExclusive)
        }
        for (phone in phones) {
            addLink(LinkAnnotation.Url(phone.url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))), phone.start, phone.end)
        }
    }
}
