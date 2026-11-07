package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow

interface SoundEventDetecting {
    fun observations(audio: Flow<FloatArray>): Flow<SoundObservation>

    val windowSamples: Int get() = YamnetSounds.SAMPLES_PER_WINDOW
}
