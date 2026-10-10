package com.arbelonson.ozen.core

import kotlin.math.abs
import kotlin.math.floor

internal data class CivilDate(val year: Int, val month: Int, val day: Int) {
    companion object {
        fun fromDaysSinceEpoch(days: Long): CivilDate {
            val z = days + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)
            return CivilDate(year.toInt(), month.toInt(), day.toInt())
        }

        fun localDay(timestamp: Double, utcOffsetSeconds: Int): Long {
            val days = floor((floor(timestamp) + utcOffsetSeconds) / 86_400)
            if (!days.isFinite() || abs(days) >= 1e12) return 0
            return days.toLong()
        }

        fun weekday(ofDay: Long): Int = (((ofDay + 4) % 7 + 7) % 7).toInt()
    }
}
