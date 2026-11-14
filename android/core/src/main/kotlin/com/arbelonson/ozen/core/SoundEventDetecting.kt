package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow

interface SoundEventDetecting {
    fun observations(audio: Flow<FloatArray>): Flow<SoundObservation>

    fun observations(audio: Flow<FloatArray>, availability: (Boolean) -> Unit): Flow<SoundObservation> = observations(audio)
}
