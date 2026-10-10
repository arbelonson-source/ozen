package com.arbelonson.ozen.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil

object ByteSize {
    fun text(bytes: Long): String {
        val kilobytes = ceil(bytes.toDouble() / 1_000)
        if (kilobytes < 1_000) return "${kilobytes.toInt()} KB"
        val megabytes = Math.round(bytes.toDouble() / StorageSpaceGate.BYTES_PER_MEGABYTE)
        if (megabytes >= 1_000) {
            val gigabytes = BigDecimal(megabytes.toDouble() / 1_000).setScale(1, RoundingMode.HALF_EVEN)
            return "${gigabytes.toPlainString()} GB"
        }
        return "$megabytes MB"
    }
}
