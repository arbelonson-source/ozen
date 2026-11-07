package com.arbelonson.ozen.core

import kotlinx.coroutines.flow.Flow

interface SoundEventDetecting {
    fun observations(audio: Flow<FloatArray>): Flow<SoundObservation>
}
