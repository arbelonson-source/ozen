package com.arbelonson.ozen

import android.content.Context
import android.os.Build
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.CaptionPipeline
import com.arbelonson.ozen.core.HomeServerEngine
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SpeakerEmbedding
import com.arbelonson.ozen.core.TranscriptionEngine
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.WebSocketConnector
import com.arbelonson.ozen.whisper.ModelFileLoader
import com.arbelonson.ozen.whisper.SileroVoiceScorer
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class CaptionSession(
    private val context: Context,
    private val settings: SettingsHolder,
    private val homeServerCode: HomeServerCodeStore,
) {
    private val scope = MainScope()
    private val connector = WebSocketConnector()
    private val voiceScorer by lazy { SileroVoiceScorer.fromAssets(context) }
    private var interruptedBySystem = false
    val audio = AndroidAudioCapture(context)
    val pipeline = CaptionPipeline(
        scope = scope,
        audio = audio,
        engineFactory = ::engine,
        embedder = NoVoicePrints,
        availableStorageBytes = { context.filesDir.usableSpace },
    )

    init {
        audio.onInterruption = { active ->
            interruptedBySystem = active
            pipeline.systemInterruptionChanged(active)
            publish()
        }
        pipeline.onPhaseChange = { publish() }
        pipeline.onCaptionsChanged = ::publish
    }

    fun listen() {
        scope.launch {
            val current = settings.current.value
            when (pipeline.phase) {
                PipelinePhase.Idle -> pipeline.start(current)
                PipelinePhase.Paused -> pipeline.resume(current)
                is PipelinePhase.Failed -> pipeline.retryAfterTap(current)
                else -> Unit
            }
        }
    }

    fun pause() = pipeline.pause()

    private fun publish() = CaptionState.show(
        phase = pipeline.phase,
        engine = pipeline.activeEngineKind,
        interruptedBySystem = interruptedBySystem,
        scheduledRetry = pipeline.scheduledRetry,
        segments = pipeline.segments,
    )

    private fun engine(settings: AppSettings): TranscriptionEngine = when (settings.engine) {
        TranscriptionEngineKind.HomeServer -> HomeServerEngine(
            address = settings.homeServerAddress,
            token = homeServerCode::read,
            connector = connector,
            client = "Ozen ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}, Android ${Build.VERSION.RELEASE}",
            beam = settings.homeServerBeam,
        )
        TranscriptionEngineKind.Cloud -> settings.cloudProvider.engine(
            model = settings.chosenCloudModel,
            connector = connector,
            apiKey = { null },
        )
        TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.AppleSpeech -> OnDeviceWhisperEngine(
            modelName = MODEL_FILE,
            loader = ModelFileLoader(File(context.filesDir, MODEL_FILE), context.applicationInfo.nativeLibraryDir),
            voiceScorerFactory = ::voiceScore,
        )
    }

    private fun voiceScore(): ((FloatArray) -> Float?)? {
        val scorer = voiceScorer ?: return null
        scorer.reset()
        return { input -> scorer.score(input) }
    }

    private object NoVoicePrints : SpeakerEmbedding {
        override fun embed(samples: FloatArray, sampleRate: Double): FloatArray? = null
    }

    companion object {
        const val MODEL_FILE = "ggml-ozen-a3-q5_0.bin"
    }
}
