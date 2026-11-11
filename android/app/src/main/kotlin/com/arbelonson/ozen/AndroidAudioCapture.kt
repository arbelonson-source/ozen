package com.arbelonson.ozen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.AndroidInputChoice
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPermission
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.EnergyVoiceDetector
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.PhoneSoundListening
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
 *
 * On Android 10 and later the phone's own sound is one more input, after
 * the microphones: what other apps play, heard once she has agreed in the
 * system's sharing dialog. It is never the saved choice, and it ends with
 * the captions or when the system stops the sharing.
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

    /** The listed input the recording is really routed to, once it runs; null when the system can't say. */
    override var inputInUse: AudioInputDescriptor? = null
        private set

    override var onInputsChanged: (() -> Unit)? = null
    override var onCaptureLost: (() -> Unit)? = null

    /** Told once when the system ends the sharing and the microphone is back. */
    var onPhoneSoundStopped: (() -> Unit)? = null

    /** True while a call silences the recording, false once it is heard again. */
    var onInterruption: ((Boolean) -> Unit)? = null

    private var preferredInputUID: String? = null
    private var devices: Map<String, AudioDeviceInfo> = emptyMap()
    private var deviceIds: List<Pair<Int, AudioInputDescriptor>> = emptyList()
    private var listening = false
    private val phoneSound = PhoneSoundListening()
    @Volatile
    private var projection: MediaProjection? = null

    // Counts changes of what is recorded, so the recording thread swaps it.
    @Volatile
    private var sourceVersion = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { projectionEnded() }
        }
    }

    // Not reset when a recording starts: the new recording's first report
    // tells whether the call is still on.
    @Volatile
    private var silenced = false

    /** The phone's audio mode, read to tell a call's end with nothing recording; a seam for tests. */
    internal var audioMode: () -> Int = { manager.mode }

    /** One read from the recording; a seam for tests to make it fail. */
    internal var readChunk: (AudioRecord, ShortArray) -> Int = { record, buffer -> record.read(buffer, 0, buffer.size) }

    // With no recording the silencing can't be heard to lift, so the call's
    // end is told by the phone leaving call mode, as the iPhone tells it
    // whether or not it is recording: captions that failed or were stopped
    // during the call aren't left waiting on it.
    private val callEndCheck = object : Runnable {
        override fun run() {
            if (record != null || !silenced) return
            if (audioMode() == AudioManager.MODE_NORMAL) silencedChanged(false) else main.postDelayed(this, CALL_CHECK_MS)
        }
    }

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
            silencedChanged(ours.isClientSilenced)
        }
    }

    private val routingListener = AudioRouting.OnRoutingChangedListener { main.post { routingChanged() } }

    internal fun routingChanged() {
        val now = if (phoneSound.active) null else routedInput(record?.routedDevice?.id, deviceIds)
        if (now == inputInUse) return
        inputInUse = now
        onInputsChanged?.invoke()
    }

    private fun retire(finished: AudioRecord) {
        runCatching { finished.removeOnRoutingChangedListener(routingListener) }
        runCatching { finished.stop() }
        finished.release()
    }

    internal fun silencedChanged(now: Boolean) {
        if (!phoneSound.heedsMicrophoneSilence) return
        if (now == silenced) return
        silenced = now
        onInterruption?.invoke(now)
    }

    override suspend fun requestPermission(): AudioPermission =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AudioPermission.Granted
        } else {
            AudioPermission.Denied
        }

    override suspend fun prepareSession(preferredInputUID: String?) {
        this.preferredInputUID = preferredInputUID?.takeUnless(PhoneSound::isPhoneSound)
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
        var seen = sourceVersion
        val first = openSource()
        if (first == null) {
            close(IllegalStateException("the microphone could not be opened"))
            return@callbackFlow
        }
        val made: AudioRecord = first
        record = made
        main.post { routingChanged() }
        running = true
        worker = thread(name = "ozen-microphone") {
            val buffer = ShortArray(CHUNK)
            var lost = false
            var current = made
            try {
                while (running) {
                    if (seen != sourceVersion) {
                        seen = sourceVersion
                        val next = openSource()
                        if (next != null) {
                            retire(current)
                            current = next
                            record = next
                            main.post { routingChanged() }
                        }
                    }
                    val read = readChunk(current, buffer)
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
                retire(current)
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
        inputInUse = null
        if (silenced) {
            main.removeCallbacks(callEndCheck)
            main.postDelayed(callEndCheck, CALL_CHECK_MS)
        }
    }

    override fun selectInput(uid: String) {
        if (PhoneSound.isPhoneSound(uid)) {
            if (!phoneSound.active) throw IllegalArgumentException("no input $uid")
            return
        }
        val device = devices[uid] ?: throw IllegalArgumentException("no input $uid")
        if (phoneSound.active) leavePhoneSound()
        preferredInputUID = uid
        selectedInputUID = uid
        record?.setPreferredDevice(device)
    }

    override fun refreshInputs() {
        val found = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { device -> portType(device.type)?.let { device to descriptor(device, it) } }
        devices = found.associate { (device, descriptor) -> descriptor.uid to device }
        deviceIds = found.map { (device, descriptor) -> device.id to descriptor }
        availableInputs = PhoneSound.inputs(found.map { it.second }, Build.VERSION.SDK_INT)
        // With nothing picked, a wired or USB microphone is used, as Android
        // and the iPhone would by themselves. Only Ozen's own last choice
        // went in before: nothing at launch, so the phone's own microphone,
        // and every recording is held to the choice, so a USB-C or lapel
        // microphone plugged in before or during captions was never heard.
        selectedInputUID = if (phoneSound.active) {
            PhoneSound.UID
        } else {
            AndroidInputChoice.resolve(availableInputs, preferredInputUID, selectedInputUID)
        }
    }

    /** She agreed in the system's dialog: [granted] is the sharing, and the phone's sound is heard from now on. */
    fun startPhoneSound(granted: MediaProjection) {
        if (phoneSound.active) leavePhoneSound()
        if (silenced) silencedChanged(false)
        phoneSound.begin(selectedInputUID)
        projection = granted
        granted.registerCallback(projectionCallback, main)
        refreshInputs()
        sourceVersion++
        onInputsChanged?.invoke()
    }

    /** The captions ended, or the app is closing: the microphone again, nothing said. */
    fun endPhoneSound() {
        if (!phoneSound.active) return
        leavePhoneSound()
        refreshInputs()
        sourceVersion++
        onInputsChanged?.invoke()
    }

    internal fun projectionEnded() {
        val stop = phoneSound.projectionStopped() ?: return
        releaseProjection()
        selectedInputUID = stop.returnTo
        refreshInputs()
        sourceVersion++
        onInputsChanged?.invoke()
        onPhoneSoundStopped?.invoke()
    }

    private fun leavePhoneSound() {
        val back = phoneSound.leave()
        releaseProjection()
        selectedInputUID = back
        sourceVersion++
    }

    private fun releaseProjection() {
        val ended = projection ?: return
        projection = null
        runCatching { ended.unregisterCallback(projectionCallback) }
        runCatching { ended.stop() }
    }

    private fun openSource(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val size = maxOf(minimum, CHUNK * 2 * 4)
        val shared = projection
        val made = try {
            if (phoneSound.active && shared != null) phoneRecord(shared, size) else microphoneRecord(size)
        } catch (error: SecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: UnsupportedOperationException) {
            null
        }
        if (made == null || made.state != AudioRecord.STATE_INITIALIZED) {
            made?.release()
            if (phoneSound.active) main.post { projectionEnded() }
            return null
        }
        if (!phoneSound.active) selectedInputUID?.let { uid -> devices[uid]?.let { made.setPreferredDevice(it) } }
        made.startRecording()
        made.addOnRoutingChangedListener(routingListener, main)
        return made
    }

    @SuppressLint("MissingPermission")
    private fun microphoneRecord(size: Int) =
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)

    @SuppressLint("MissingPermission")
    private fun phoneRecord(shared: MediaProjection, size: Int): AudioRecord? {
        if (Build.VERSION.SDK_INT < PhoneSound.FIRST_API) return null
        val config = AudioPlaybackCaptureConfiguration.Builder(shared)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format).setBufferSizeInBytes(size).build()
    }

    private fun inputsChanged() {
        refreshInputs()
        selectedInputUID?.let { uid -> devices[uid]?.let { record?.setPreferredDevice(it) } }
        routingChanged()
        onInputsChanged?.invoke()
    }

    private fun descriptor(device: AudioDeviceInfo, type: AudioPortType): AudioInputDescriptor {
        val place = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.orEmpty() else ""
        val name = device.productName?.toString().orEmpty()
        return AudioInputDescriptor(
            uid = "${type.rawValue}:${place.ifEmpty { name }}",
            portName = name.ifEmpty { type.rawValue },
            portType = AudioPortType.named(name) ?: type,
        )
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 1_600
        internal const val CALL_CHECK_MS = 1_000L

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
