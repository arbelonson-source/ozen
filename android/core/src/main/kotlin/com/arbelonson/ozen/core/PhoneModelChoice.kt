package com.arbelonson.ozen.core

sealed class PhoneModelSwitch {
    data object Idle : PhoneModelSwitch()

    data class Downloading(val file: String, val fraction: Double) : PhoneModelSwitch()

    data class NotEnoughRoom(val file: String, val megabytes: Int) : PhoneModelSwitch()

    data class WaitingForWiFi(val file: String) : PhoneModelSwitch()

    data class Offline(val file: String) : PhoneModelSwitch()

    data class Failed(val file: String) : PhoneModelSwitch()
}

sealed class PhoneCheckState {
    data object Idle : PhoneCheckState()

    data class Running(val fraction: Float) : PhoneCheckState()

    data object Failed : PhoneCheckState()
}

object PhoneModelChoice {
    enum class Plan { UseNow, DownloadFirst }

    fun plan(chosen: PhoneModel, running: PhoneModel, installed: Set<String>): Plan =
        if (chosen.file in installed || running.file !in installed) Plan.UseNow else Plan.DownloadFirst

    fun storedFile(language: UILanguage, chosen: PhoneModel): String? =
        chosen.file.takeIf { it != PhoneModelCatalog.recommended(language).file }
}
