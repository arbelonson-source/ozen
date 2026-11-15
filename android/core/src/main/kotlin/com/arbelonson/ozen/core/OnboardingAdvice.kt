package com.arbelonson.ozen.core

object OnboardingNotificationAdvice {
    fun showsSettingsButton(allowed: Boolean?): Boolean = allowed == false

    fun afterAsking(granted: Boolean, allowedNow: Boolean): Boolean = granted && allowedNow

    fun keptAcrossRebuild(allowedNow: Boolean, remembered: Boolean?): Boolean? = when {
        allowedNow -> true
        remembered == false -> false
        else -> null
    }
}

object OnboardingModelNote {
    fun showsWifiNote(chosenFile: String, installed: Set<String>, mobileDataAllowed: Boolean): Boolean = chosenFile !in installed && !mobileDataAllowed
}

object OnboardingMicrophoneAdvice {
    fun keptAcrossRebuild(grantedNow: Boolean, remembered: Boolean?): Boolean? = when {
        grantedNow -> true
        remembered == false -> false
        else -> null
    }
}
