package com.arbelonson.ozen

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

object OzenTypography {
    fun of(base: Typography): Typography =
        base.copy(bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp))
}
