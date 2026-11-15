package com.arbelonson.ozen.core

object OnboardingNotificationAdvice {
    fun showsSettingsButton(allowed: Boolean?): Boolean = allowed == false

    /** What the walkthrough remembers after a rebuild: a refusal stays a refusal, a yes or a never-asked is looked at again. */
    fun keptAcrossRebuild(allowedNow: Boolean, remembered: Boolean?): Boolean? = when {
        allowedNow -> true
        remembered == false -> false
        else -> null
    }
}

object OnboardingModelNote {
    /** The Wi-Fi sentence belongs to a model that still has to be downloaded. */
    fun showsWifiNote(chosenFile: String, installed: Set<String>): Boolean = chosenFile !in installed
}

object OnboardingMicrophoneAdvice {
    fun keptAcrossRebuild(grantedNow: Boolean, remembered: Boolean?): Boolean? = when {
        grantedNow -> true
        remembered == false -> false
        else -> null
    }
}
