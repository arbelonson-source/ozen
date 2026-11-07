package com.arbelonson.ozen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun typingValue(text: String, held: TextFieldValue): TextFieldValue =
    if (held.text == text) held else TextFieldValue(text, TextRange(text.length))
