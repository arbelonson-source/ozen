package com.arbelonson.ozen

import com.arbelonson.ozen.core.tr

object HourStepperText {
    fun start(): String = tr("התחלה", "Start")

    fun end(): String = tr("סיום", "End")

    fun button(later: Boolean, row: String): String {
        val sign = if (later) "+" else "−"
        val move = if (later) tr("שעה מאוחרת יותר", "one hour later") else tr("שעה מוקדמת יותר", "one hour earlier")
        return "$sign $row: $move"
    }
}
