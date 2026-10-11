package com.arbelonson.ozen.core

import java.util.regex.Pattern

internal fun unicodePattern(regex: String, flags: Int = 0): Pattern = try {
    Pattern.compile(regex, flags or Pattern.UNICODE_CHARACTER_CLASS)
} catch (refused: IllegalArgumentException) {
    Pattern.compile(regex, flags)
}
