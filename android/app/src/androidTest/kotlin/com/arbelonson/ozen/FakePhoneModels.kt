package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneCheckState
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.PhoneModelSwitch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

class FakePhoneModels(private val settings: SettingsHolder) : PhoneModels {
    override val switching = MutableStateFlow<PhoneModelSwitch>(PhoneModelSwitch.Idle)
    override val check = MutableStateFlow<PhoneCheckState>(PhoneCheckState.Idle)
    override val installed = MutableStateFlow<Set<String>>(emptySet())
    override val partial = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val captionsBusy = MutableStateFlow(false)
    override val totalMemoryBytes: Long? = 8_000_000_000

    val missing = ConcurrentHashMap<String, Int>()
    val chosen = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val noRoomFor = ConcurrentHashMap<String, Int>()

    override fun refreshInstalled() {}

    override fun shortfallMegabytes(file: String): Int? = missing[file]

    override fun choose(file: String, byHand: Boolean) {
        chosen += file to byHand
        val short = noRoomFor[file]
        if (short != null) {
            switching.value = PhoneModelSwitch.NotEnoughRoom(file, short)
            return
        }
        settings.change { it.phoneModelFile = file }
    }

    override fun resumeWaiting() {}

    override suspend fun delete(file: String): PhoneModelDeletion.Result = PhoneModelDeletion.Result.Refused

    override fun runCheck(thenChooseBest: Boolean) {}
}
