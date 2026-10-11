package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.arbelonson.ozen.core.NumberEmphasis
import com.arbelonson.ozen.core.UncertainWords

val NUMBER_YELLOW = Color(red = 1.0f, green = 0.88f, blue = 0.2f)

const val DOUBTFUL_WORD = "doubtful"

fun captionText(shown: String, emphasizeNumbers: Boolean, numberColor: Color?, doubtfulWords: List<String> = emptyList()): AnnotatedString {
    val ranges = if (emphasizeNumbers) NumberEmphasis.ranges(shown) else emptyList()
    val doubted = if (doubtfulWords.isEmpty()) emptyList() else UncertainWords.ranges(shown, doubtfulWords)
    if (ranges.isEmpty() && doubted.isEmpty()) return AnnotatedString(shown)
    return buildAnnotatedString {
        append(shown)
        for (range in ranges) {
            addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = numberColor ?: Color.Unspecified), range.start, range.endExclusive)
        }
        for (range in doubted) {
            addStringAnnotation(DOUBTFUL_WORD, "", range.start, range.endExclusive)
        }
    }
}
