package com.arbelonson.ozen

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresApi
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.SpeakingOutput

internal object SpeakerChoice {
    @Volatile var chosen = false
        private set
    private var routed = false
    private var callAttributes = false
    private var devicesSeen: Set<Int>? = null

    val canChoose: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun choose() {
        chosen = canChoose
    }

        fun outputs(audio: AudioManager): List<SpeakingOutput> {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        val ids = devices.map { it.id }.toSet()
        if (devicesSeen != null && devicesSeen != ids) chosen = false
        devicesSeen = ids
        if (chosen) return listOf(SpeakingOutput(SpeakingOutput.Kind.PhoneSpeaker, ""))
        return outputsFor(devices.map { Device(it.type, it.productName?.toString().orEmpty()) })
    }

    internal data class Device(val type: Int, val name: String)

        internal fun outputsFor(devices: List<Device>): List<SpeakingOutput> {
        val external = devices.mapNotNull { device -> kindOf(device)?.let { SpeakingOutput(it, device.name) } }
        return external.ifEmpty { listOf(SpeakingOutput(SpeakingOutput.Kind.PhoneSpeaker, "")) }
    }

    private fun kindOf(device: Device): SpeakingOutput.Kind? = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
            if (AudioPortType.of(AudioPortType.Bluetooth, device.name) == AudioPortType.HearingAid) SpeakingOutput.Kind.HearingAid else SpeakingOutput.Kind.Bluetooth
        AudioDeviceInfo.TYPE_HEARING_AID -> SpeakingOutput.Kind.HearingAid
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> SpeakingOutput.Kind.Wired
        AudioDeviceInfo.TYPE_BUS -> SpeakingOutput.Kind.Car
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_HDMI -> SpeakingOutput.Kind.Other
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_TELEPHONY -> null
        else -> SpeakingOutput.Kind.Other
    }

    fun beforeSpeaking(context: Context, engine: TextToSpeech) {
        val audio = context.getSystemService(AudioManager::class.java)
        val routedNow = if (chosen && audio != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            engine.setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
            callAttributes = true
            toSpeaker(audio)
        } else {
            false
        }
        routed = routedNow
        if (chosen && !routedNow) chosen = false
        if (!routedNow && callAttributes) {
            engine.setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA))
            callAttributes = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun toSpeaker(audio: AudioManager): Boolean {
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return false
        return audio.setCommunicationDevice(speaker)
    }

    fun afterSpeaking(context: Context) {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
    }

    private fun attributes(usage: Int) = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
}
