package com.arbelonson.ozen.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor

class QuietHours(
    val isEnabled: Boolean = false,
    startHour: Int = 22,
    endHour: Int = 7,
) {
    val startHour: Int = clamped(startHour)
    val endHour: Int = clamped(endHour)

    fun isQuiet(now: Double, utcOffsetSeconds: Int): Boolean {
        if (!isEnabled) return false
        val localSeconds = floor(now).toLong() + utcOffsetSeconds
        val secondsIntoDay = ((localSeconds % 86_400) + 86_400) % 86_400
        val hour = (secondsIntoDay / 3_600).toInt()
        if (startHour == endHour) return true
        return if (startHour < endHour) hour >= startHour && hour < endHour else hour >= startHour || hour < endHour
    }

    fun toJson(): String = buildJsonObject {
        put("isEnabled", isEnabled)
        put("startHour", startHour)
        put("endHour", endHour)
    }.toString()

    override fun equals(other: Any?): Boolean =
        other is QuietHours && isEnabled == other.isEnabled && startHour == other.startHour && endHour == other.endHour

    override fun hashCode(): Int = (isEnabled.hashCode() * 31 + startHour) * 31 + endHour

    override fun toString(): String = "QuietHours(isEnabled=$isEnabled, startHour=$startHour, endHour=$endHour)"

    companion object {
        val default = QuietHours()

        private fun clamped(hour: Int): Int = minOf(maxOf(hour, 0), 23)

        fun fromJson(json: String): QuietHours {
            val container = Json.parseToJsonElement(json).jsonObject
            return QuietHours(
                isEnabled = container.lenientBoolean("isEnabled") ?: default.isEnabled,
                startHour = container.lenientInt("startHour") ?: default.startHour,
                endHour = container.lenientInt("endHour") ?: default.endHour,
            )
        }
    }
}

private fun JsonObject.lenientBoolean(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) null else value.content.toBooleanStrictOrNull()
}

private fun JsonObject.lenientInt(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) null else value.content.toIntOrNull()
}
