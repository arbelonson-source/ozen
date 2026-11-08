package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor

internal fun routedInput(deviceId: Int?, listed: List<Pair<Int, AudioInputDescriptor>>): AudioInputDescriptor? =
    deviceId?.let { id -> listed.firstOrNull { it.first == id }?.second }
