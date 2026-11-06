package com.arbelonson.ozen

import com.arbelonson.ozen.core.ModelLoadCrash
import com.arbelonson.ozen.core.PhoneModel
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.whisper.ModelLoadMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LoadCrashLaunch(offer: PhoneModel?) {
    private val offered = MutableStateFlow(offer)

    val showing: StateFlow<PhoneModel?> get() = offered

    fun mayStartCaptions(): Boolean = offered.value == null

    fun dismiss() {
        offered.value = null
    }

    companion object {
        fun from(marker: ModelLoadMarker, current: PhoneModel, language: UILanguage, totalMemoryBytes: Long?) =
            LoadCrashLaunch(ModelLoadCrash.offer(marker.take(), current, language, totalMemoryBytes))
    }
}
