package com.arbelonson.ozen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.AudioRoutePolicy
import com.arbelonson.ozen.core.EnergyVoiceDetector
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The microphone for the caption pipeline: 16 kHz mono from the source
 * Android tunes for speech recognition, in tenth-of-a-second chunks.
 *
 * A phone call doesn't take the microphone away on Android, it silences
 * it: the recording carries on, all zeros. That is reported through
 * [onInterruption], the way the iPhone reports a call taking its
 * microphone. The recording is kept running through the call, so the
 * call's end is heard too.
 */
class AndroidAudioCapture(private val context: Context) : AudioCapturing {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    override var availableInputs: List<AudioInputDescriptor> = emptyList()
        private set
    override var selectedInputUID: String? = null
        private set

    @Volatile
    override var inputLevel: Float = 0f
        private set

    override var onInputsChanged: (() -> Unit)? = null
    override var onCaptureLost: (() -> Unit)? = null

    /** True while a call silences the recording, false once it is heard again. */
    var onInterruption: ((Boolean) -> Unit)? = null

    private var preferredInputUID: String? = null
    private var devices: Map<String, AudioDeviceInfo> = emptyMap()
    private var listening = false

    // Not reset when a recording starts: a call that ended while nothing
    // was recording is only noticed when the next recording is heard.
    private var silenced = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = inputsChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = inputsChanged()
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val session = record?.audioSessionId ?: return
            val ours = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            if (ours.isClientSilenced != silenced) {
                silenced = ours.isClientSilenced
                onInterruption?.invoke(silenced)
            }
        }
    }

    override suspend fun requestPermission(): AudioPermission =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AudioPermission.Granted
        } else {
            AudioPermission.Denied
        }

    override suspend fun prepareSession(preferredInputUID: String?) {
        this.preferredInputUID = preferredInputUID
        if (!listening) {
            listening = true
            manager.registerAudioDeviceCallback(deviceCallback, main)
            manager.registerAudioRecordingCallback(recordingCallback, main)
        }
        refreshInputs()
    }

    @SuppressLint("MissingPermission")
    override fun startCapture(): Flow<FloatArray> = callbackFlow {
        stopCapture()
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val made = if (minimum > 0) {
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, CHUNK * 2 * 4),
                )
            } catch (error: SecurityException) {
                null
            }
        } else {
            null
        }
        if (made == null || made.state != AudioRecord.STATE_INITIALIZED) {
            made?.release()
            close(IllegalStateException("the microphone could not be opened"))
            return@callbackFlow
        }
        selectedInputUID?.let { uid -> devices[uid]?.let { made.setPreferredDevice(it) } }
        made.startRecording()
        record = made
        running = true
        worker = thread(name = "ozen-microphone") {
            val buffer = ShortArray(CHUNK)
            var lost = false
            try {
                while (running) {
                    val read = made.read(buffer, 0, CHUNK)
                    if (read < 0) {
                        lost = true
                        break
                    }
                    if (read == 0) continue
                    val chunk = FloatArray(read) { buffer[it] / 32768f }
                    inputLevel = EnergyVoiceDetector.meterLevel(EnergyVoiceDetector.rms(chunk))
                    trySend(chunk)
                }
            } finally {
                runCatching { made.stop() }
                made.release()
                inputLevel = 0f
                if (lost && running) main.post { onCaptureLost?.invoke() }
                channel.close()
            }
        }
        awaitClose { stopCapture() }
    }

    override fun stopCapture() {
        running = false
        val current = worker
        worker = null
        if (current != null && current != Thread.currentThread()) current.join(1_000)
        record = null
    }

    override fun selectInput(uid: String) {
        val device = devices[uid] ?: throw IllegalArgumentException("no input $uid")
        preferredInputUID = uid
        selectedInputUID = uid
        record?.setPreferredDevice(device)
    }

    override fun refreshInputs() {
        val found = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { device -> portType(device.type)?.let { device to descriptor(device, it) } }
        devices = found.associate { (device, descriptor) -> descriptor.uid to device }
        availableInputs = found.map { it.second }
        selectedInputUID = AudioRoutePolicy.resolveSelection(
            available = availableInputs,
            preferredUID = preferredInputUID,
            currentUID = selectedInputUID,
        )
    }

    private fun inputsChanged() {
        refreshInputs()
        selectedInputUID?.let { uid -> devices[uid]?.let { record?.setPreferredDevice(it) } }
        onInputsChanged?.invoke()
    }

    private fun descriptor(device: AudioDeviceInfo, type: AudioPortType): AudioInputDescriptor {
        val place = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.orEmpty() else ""
        val name = device.productName?.toString().orEmpty()
        return AudioInputDescriptor(
            uid = "${type.rawValue}:${place.ifEmpty { name }}",
            portName = name.ifEmpty { type.rawValue },
            portType = type,
        )
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 1_600

        /** Inputs a person speaks into; tuners, call audio and loopbacks are left out. */
        fun portType(type: Int): AudioPortType? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioPortType.BuiltInMic
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioPortType.Bluetooth
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioPortType.Wired
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioPortType.Usb
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioPortType.HearingAid
            else -> null
        }
    }
}
