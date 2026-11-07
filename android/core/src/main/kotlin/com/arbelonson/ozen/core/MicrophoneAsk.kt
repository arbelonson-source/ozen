package com.arbelonson.ozen.core

enum class MicrophoneAsk {
    Granted,
    AskAgain,
    OpenSettings;

    companion object {
        fun next(granted: Boolean, canAskAgain: Boolean): MicrophoneAsk = when {
            granted -> Granted
            canAskAgain -> AskAgain
            else -> OpenSettings
        }
    }
}
