package com.arbelonson.ozen.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PhoneSpeed(val gflops: Double, val threads: Int, val measuredAt: Double, val device: String? = null) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("gflops", gflops)
        put("threads", threads)
        put("measuredAt", measuredAt)
        device?.let { put("device", it) }
    }

    companion object {
        fun fromJsonObject(element: JsonObject): PhoneSpeed? {
            val gflops = number(element["gflops"]) ?: return null
            val threads = number(element["threads"])?.toInt() ?: return null
            val measuredAt = number(element["measuredAt"]) ?: return null
            return if (gflops > 0 && threads > 0) PhoneSpeed(gflops, threads, measuredAt, (element["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content) else null
        }

        private fun number(element: Any?): Double? =
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
