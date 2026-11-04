package com.arbelonson.ozen.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LiveMeasurement(val file: String, val averageSeconds: Double, val passes: Int, val gflops: Double, val measuredAt: Double) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("file", file)
        put("averageSeconds", averageSeconds)
        put("passes", passes)
        put("gflops", gflops)
        put("measuredAt", measuredAt)
    }

    companion object {
        fun fromJsonObject(element: JsonObject): LiveMeasurement? {
            val file = (element["file"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val averageSeconds = number(element["averageSeconds"]) ?: return null
            val passes = number(element["passes"])?.toInt() ?: return null
            val gflops = number(element["gflops"]) ?: return null
            val measuredAt = number(element["measuredAt"]) ?: return null
            return if (averageSeconds > 0 && passes > 0 && gflops > 0) LiveMeasurement(file, averageSeconds, passes, gflops, measuredAt) else null
        }

        private fun number(element: Any?): Double? =
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
