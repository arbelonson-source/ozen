package com.arbelonson.ozen

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.SpeakingOutput

/**
 * Where spoken replies come out, and the one choice the Say screen offers:
 * say them on the phone's own speaker although a headset or hearing aid is
 * connected. Not the default, since some people use a Bluetooth speaker on
 * purpose. The choice is for a session: a device coming or going ends it.
 *
 * The speech engine has no output device of its own, so the choice plays
 * replies as a call's audio and points that at the speaker; the volume is
 * then the call volume, not the media volume. Before Android 12 there is
 * no such control and only the notice is shown.
 */
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

    /** Read once a second by the Say screen; also ends the choice when the set of devices changes. */
    fun outputs(audio: AudioManager): List<SpeakingOutput> {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
        val ids = devices.map { it.id }.toSet()
        if (devicesSeen != null && devicesSeen != ids) chosen = false
        devicesSeen = ids
        if (chosen) return listOf(SpeakingOutput(SpeakingOutput.Kind.PhoneSpeaker, ""))
        return outputsFor(devices.map { Device(it.type, it.productName?.toString().orEmpty()) })
    }

    internal data class Device(val type: Int, val name: String)

    /** Media goes to an external device when one is connected, else to the speaker. */
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
        else -> null
    }

    fun beforeSpeaking(context: Context, engine: TextToSpeech) {
        val audio = context.getSystemService(AudioManager::class.java)
        val speaker = if (chosen && canChoose) audio?.availableCommunicationDevices?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
        if (speaker != null && audio != null) {
            engine.setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION))
            callAttributes = true
            routed = audio.setCommunicationDevice(speaker)
        } else if (callAttributes) {
            engine.setAudioAttributes(attributes(AudioAttributes.USAGE_MEDIA))
            callAttributes = false
        }
    }

    fun afterSpeaking(context: Context) {
        if (!routed) return
        routed = false
        if (canChoose) context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
    }

    private fun attributes(usage: Int) = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
}
