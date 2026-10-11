package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.arbelonson.ozen.core.NumberEmphasis

val NUMBER_YELLOW = Color(red = 1.0f, green = 0.88f, blue = 0.2f)

fun captionText(shown: String, emphasizeNumbers: Boolean, numberColor: Color?): AnnotatedString {
    val ranges = if (emphasizeNumbers) NumberEmphasis.ranges(shown) else emptyList()
    if (ranges.isEmpty()) return AnnotatedString(shown)
    return buildAnnotatedString {
        append(shown)
        for (range in ranges) {
            addStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = numberColor ?: Color.Unspecified), range.start, range.endExclusive)
        }
    }
}
