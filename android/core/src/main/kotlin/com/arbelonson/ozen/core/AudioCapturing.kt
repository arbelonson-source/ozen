package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow

enum class AudioPermission {
    Granted,
    Denied,
}

/**
 * Everything the caption pipeline needs from the microphone side, as an
 * interface so the pipeline's startup sequence can be tested end to end
 * with a fake that yields synthetic audio and fake input lists. The real
 * implementation is the thin, hardware-facing layer that the Android app
 * provides. Its members are used from the main thread only.
 *
 * Deliberately split into [prepareSession] and [startCapture]: listing
 * microphones only needs the session, not the recording, so the mic picker
 * can be populated immediately on launch instead of after a model finishes
 * downloading, the exact gap that made the first build look broken.
 */
interface AudioCapturing {
    val availableInputs: List<AudioInputDescriptor>
    val selectedInputUID: String?

    /**
     * Live input level in 0...1, for the meter in the mic picker so the
     * user can see at a glance whether the mic they picked is actually
     * hearing anything.
     */
    val inputLevel: Float

    /**
     * Called on the main thread whenever the system reports a route
     * change (headphones reconnecting, a USB mic unplugged, ...).
     */
    var onInputsChanged: (() -> Unit)?
    var onCaptureLost: (() -> Unit)?

    suspend fun requestPermission(): AudioPermission

    /**
     * Configures and activates the audio session and enumerates inputs.
     * Safe to call more than once.
     */
    suspend fun prepareSession(preferredInputUID: String?)

    /**
     * Begins delivering 16 kHz mono Float32 chunks. Requires
     * [prepareSession] first.
     */
    fun startCapture(): Flow<FloatArray>

    fun stopCapture()

    fun selectInput(uid: String)

    /**
     * Asks the system for the current inputs again, even when no session
     * has been prepared (captions never started, or failed before the
     * microphone was set up). Must not start recording.
     */
    fun refreshInputs()
}
