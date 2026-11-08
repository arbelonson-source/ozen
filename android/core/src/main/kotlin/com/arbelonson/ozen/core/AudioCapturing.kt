package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow

enum class AudioPermission {
    Granted,
    Denied,
}

interface AudioCapturing {
    val availableInputs: List<AudioInputDescriptor>
    val selectedInputUID: String?

    /** The listed input a recording is really routed to; null when none runs or the system can't say. */
    val inputInUse: AudioInputDescriptor? get() = null

    val inputLevel: Float

    var onInputsChanged: (() -> Unit)?
    var onCaptureLost: (() -> Unit)?

    suspend fun requestPermission(): AudioPermission

    suspend fun prepareSession(preferredInputUID: String?)

    fun startCapture(): Flow<FloatArray>

    fun stopCapture()

    fun selectInput(uid: String)

    fun refreshInputs()
}
