package com.arbelonson.ozen

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioCapturing
import com.arbelonson.ozen.core.CaptionAnnouncer
import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionPipeline
import com.arbelonson.ozen.core.DeviceHeat
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.HTTPFileFetcher
import com.arbelonson.ozen.core.HistoryRetention
import com.arbelonson.ozen.core.HomeServer
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.BackupModel
import com.arbelonson.ozen.core.BackupModelStatus
import com.arbelonson.ozen.core.BatteryAdvisor
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.CaptionsStopAnnouncer
import com.arbelonson.ozen.core.HomeServerEngine
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundAlertPreferences
import com.arbelonson.ozen.core.SoundEvent
import com.arbelonson.ozen.core.KeywordAlert
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.OnDeviceWhisperEngine
import com.arbelonson.ozen.core.ModelDownloadGate
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.ProblemAudioStore
import com.arbelonson.ozen.core.ProblemSnapshot
import com.arbelonson.ozen.core.RecentConversation
import com.arbelonson.ozen.core.RecordingImport
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.SavedSpeaker
import com.arbelonson.ozen.core.SavingTroubleNotice
import com.arbelonson.ozen.core.SessionJournal
import com.arbelonson.ozen.core.SpeakerProfile
import com.arbelonson.ozen.core.StorageSpaceGate
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptHistoryWriter
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngine
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.VocabularyHints
import com.arbelonson.ozen.core.WebSocketConnector
import com.arbelonson.ozen.core.PrivatePath
import com.arbelonson.ozen.core.PrivateRoute
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.arbelonson.ozen.core.WhisperModelCatalog
import com.arbelonson.ozen.whisper.ModelFileLoader
import com.arbelonson.ozen.whisper.PhoneSpeedMeter
import com.arbelonson.ozen.whisper.SileroVoiceScorer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptionSession(
    private val context: Context,
    private val settings: SettingsHolder,
    private val homeServerCode: HomeServerCodeStore,
    private val cloudKeys: CloudKeyStore,
    val audio: AudioCapturing = AndroidAudioCapture(context),
    private val modelFolder: File = context.filesDir,
    historyFolder: File = File(context.filesDir, HISTORY_FOLDER),
    journalFile: File = File(context.filesDir, JOURNAL_FILE),
    problemAudioFolder: File = File(context.filesDir, PROBLEM_AUDIO_FOLDER),
) : DiagnosticsSource, SpeakerBook, PhoneBackup {
    private val scope = MainScope()
    private val connector = WebSocketConnector(privateRoute = { host -> homeNetwork(host)?.let { network -> PrivatePath(network.socketFactory) { network.getAllByName(it).toList() } } })
    private fun homeNetwork(host: String): Network? {
        if (!HomeServer.isPrivate(host)) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            fun transports(network: Network): Set<PrivateRoute.Transport> {
                val caps = manager.getNetworkCapabilities(network) ?: return emptySet()
                return buildSet {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(PrivateRoute.Transport.Wifi)
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(PrivateRoute.Transport.Ethernet)
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(PrivateRoute.Transport.Cellular)
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(PrivateRoute.Transport.Vpn)
                }
            }
            val default = manager.activeNetwork?.let(::transports).orEmpty()
            PrivateRoute.choose(host, default, manager.allNetworks.map { it to transports(it) })
        } catch (_: RuntimeException) {
            null
        }
    }

    private val voiceScorer by lazy { SileroVoiceScorer.fromAssets(context) }
    private var interruptedBySystem = false

    // Captions from the home computer or the cloud that stopped when the
    // internet went come back as soon as it does, as on the iPhone, not
    // only while their half-minute of retries lasts. A home computer on
    // the same Wi-Fi needs no internet, so any network counts as connected.
    private val network = AndroidNetworkMonitor(context)

    /** The connection as the model downloads see it piece by piece; a seam for tests. */
    internal var networkNow: () -> NetworkConditions? = { network.current }

    /** The phone's free space, as the model downloads see it. */
    var freeSpace: () -> Long = { context.filesDir.usableSpace }
    val pipeline = CaptionPipeline(
        scope = scope,
        audio = audio,
        engineFactory = ::engine,
        embedder = CamPlusPlusEmbedder(context),
        soundDetector = YamnetSoundDetector(context),
        availableStorageBytes = { freeSpace() },
        network = network,
    )
    private val historyStore = TranscriptHistoryStore(historyFolder)
    private val attention = KeywordAttention()
    private val announcer = CaptionAnnouncer()
    private val awayAlerts = AwayAlerts()
    private val notifier = AlertNotifier(context)
    private val stoppedCaptions = StoppedCaptionsNotice()
    private val savingTrouble = SavingTroubleNotice()

    // Readings count from asking for captions until they are stopped or
    // paused, failures and retries included (see `BatteryAdvisor.watches`).
    private val stopAnnouncer = CaptionsStopAnnouncer()
    private val batteryWatch = BatteryWatch(show = CaptionState::battery, warned = ::batteryWarningRaised)
    private val batteryReadings = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }

    // Heat and battery saver both slow the captions with nothing on screen
    // saying so: the journal notes each change, as on the iPhone.
    private val power = context.getSystemService(PowerManager::class.java)
    private val batterySaving = object : BroadcastReceiver() {
        // Only a change is noted, as the iPhone's notice: a session just
        // started on the emulator heard "battery saver off" while it was
        // already off, a word sent before the session began.
        override fun onReceive(context: Context, intent: Intent) {
            val now = power.isPowerSaveMode
            if (now == savingPower) return
            savingPower = now
            note("power: ${deviceState()}")
        }
    }
    private var heat: Any? = null

    // What the phone's model paces its live passes by, read between passes
    // many times a second: kept from the heat listener and the battery saver
    // broadcast rather than asked of the system each time.
    @Volatile private var heatNow = DeviceHeat.Nominal

    @Volatile private var savingPower = power.isPowerSaveMode

    /** The phone's own voice, and which languages it has one for. */
    val voice = PhoneVoice(context)

    /**
     * The phone saying something for her, as on the iPhone: captions pause
     * while it talks, so the microphone doesn't caption the phone's own
     * voice, and come back by themselves once it is done, unless she paused
     * or resumed them by hand meanwhile.
     */
    private val replies = SpokenReplies(
        voice,
        scope,
        object : SpokenReplies.Captions {
            override val isListening get() = pipeline.phase.isListening
            override val isPaused get() = pipeline.phase == PipelinePhase.Paused
            override val duringCall get() = interruptedBySystem

            override fun pause() = pipeline.pause()

            override fun resume() {
                scope.launch { pipeline.resume(settings.current.value) }
            }
        },
    )

    /**
     * What happened to the captions, kept on the phone past the app's
     * end, for the diagnostics report: the pipeline's events, the app
     * starting and leaving the screen, and the problems she marks.
     */
    private val journal = SessionJournal(journalFile)

    /** The last 30 seconds of sound kept with each marked problem; the app has no backups for it to leak into. */
    private val problemAudio = ProblemAudioStore(problemAudioFolder)

    /**
     * Whether this run of the app has started the captions on its own yet.
     * As on the iPhone, the caption screen's first appearance starts them,
     * once: coming back to it, or the screen being rebuilt, does not.
     */
    internal var launchHandled = false

    fun takeFirstAppearance(): Boolean {
        val first = !launchHandled
        launchHandled = true
        return first
    }

    private var screensInFront = 0

    /** A caption screen came to the front; true if the app was away until now. */
    fun screenStarted(): Boolean {
        screensInFront += 1
        val cameBack = !appIsActive
        appIsActive = true
        if (cameBack) {
            awayAlerts.takePostedIdentifiers().forEach(notifier::withdraw)
            notifier.withdrawAwayAlerts()
            if (stopAnnouncer.comingBack(pipeline.phase) == CaptionsStopAnnouncer.Event.Stopped && ScreenReader.isOn(context)) {
                ScreenReader.announce(context, stoppedText(pipeline.phase))
            }
            untold?.let { word ->
                untold = null
                tell(word)
                CaptionState.heard(word.copy(at = System.currentTimeMillis() / 1_000.0))
            }
        }
        return cameBack
    }

    /**
     * A caption screen went away; true once none is left in front. A link
     * opened from another app's task can start a second one, and Android
     * starts it before it stops the first. One [rebuilding] for a turn of
     * the phone comes straight back, and the iPhone never counts that as
     * leaving.
     */
    fun screenStopped(rebuilding: Boolean = false): Boolean {
        screensInFront = maxOf(0, screensInFront - 1)
        if (rebuilding) return false
        appIsActive = screensInFront > 0
        return !appIsActive
    }

    /**
     * Captions that stopped are tried again as she comes back, as on the
     * iPhone: back from the phone's settings with the microphone allowed,
     * she should not have to tap. A full phone waits for room instead.
     */
    val retriesOnReturn: Boolean
        get() = pipeline.phase.failure.let { it != null && it.engineUnavailability?.kind != EngineUnavailability.Kind.NotEnoughStorage }

    /** Whether the caption screen is in front: set as MainActivity starts and stops. */
    var appIsActive = false
        set(active) {
            if (active != field) note(if (active) "app back on screen" else "app left the screen (phase ${if (pipeline.phase.isListening) "listening" else "not listening"})")
            val changed = active != field
            field = active
            checkCaptionsStillRunning()
            if (changed && active) cameBack()
            // Android may end an app that is out of sight before the next autosave.
            if (changed && !active) conversations.save(ended = false)
        }

    /**
     * Back on screen, as on the iPhone: conversations past their keeping
     * time go even with saving off, which no save would sweep; a phone
     * that was too full downloads its model by itself once there is room,
     * and the backup's row stops asking for room she made meanwhile.
     */
    private fun cameBack() {
        conversations.deleteExpiredIfDue()
        refreshBackup()
        if (pipeline.phase.failure?.engineUnavailability?.kind == EngineUnavailability.Kind.NotEnoughStorage) {
            scope.launch { pipeline.appDidBecomeActive() }
        }
    }

    private val conversations = ConversationSaver(
        writer = TranscriptHistoryWriter(historyStore),
        lines = { pipeline.segments },
        isListening = { pipeline.phase.isListening },
        settings = { settings.current.value },
        transcribing = { pipeline.activeSettings.takeIf { pipeline.isCoveringForCloud } ?: settings.current.value },
        speakerName = pipeline::displayName,
        inputName = { audio.availableInputs.firstOrNull { it.uid == audio.selectedInputUID }?.portName },
        startNewConversation = pipeline::startNewConversation,
        setSaveHistory = { on -> settings.change { it.saveHistory = on } },
        scope = scope,
        problemAudio = problemAudio,
    )
    val savedConversations = object : SavedConversations {
        override fun list() = historyStore.listSummaries()

        override fun search(query: String) = historyStore.search(query)

        override fun load(id: UUID) = historyStore.load(id)

        override fun starredLines() = historyStore.starredLines()

        override fun sizeOnDisk() = historyStore.totalSizeOnDisk()

        override val lastSaveFailure: String? get() = conversations.lastSaveFailure

        // A conversation deleted is gone from the phone, so the copy last
        // shared as a file goes with it, whichever conversation that was.
        override fun delete(id: UUID) {
            conversations.delete(id)
            forgetSharedCopy()
            if (CaptionState.screen.value.recentConversation?.id == id) dismissRecentConversation()
            publish()
        }

        // Her words and the room's sound kept with a marked problem are part
        // of what she asked to delete: the caption lines leave the journal
        // (the rest of it stays) and the clips go.
        override fun deleteAll() {
            conversations.deleteAll()
            forgetSharedCopy()
            deleteMarkedCaptionLines()
            problemAudio.deleteAll()
            dismissRecentConversation()
            publish()
        }

        override fun rename(id: UUID, title: String) {
            conversations.rename(id, title)
            refreshSavingTrouble()
        }

        // A line still on the caption screen shows the change at once, as on
        // the iPhone, not only once the next caption redraws the screen.
        override fun toggleStar(sessionID: UUID, segmentID: UUID) = conversations.toggleStar(sessionID, segmentID).also { starring ->
            starring?.let { CaptionState.markStarred(segmentID, it) }
            refreshSavingTrouble()
        }

        override fun switchSaving(on: Boolean) {
            conversations.switchSaving(on)
            publish()
        }

        override suspend fun keepFor(retention: HistoryRetention) {
            settings.change { it.historyRetention = retention }
            conversations.deleteExpired()
            forgetSharedCopy()
        }

        override fun opened(id: UUID) = conversations.opened(id)

        override fun doneWith(id: UUID) = conversations.doneWith(id)

        override fun isCurrent(id: UUID) = conversations.isCurrent(id)
    }

    private fun forgetSharedCopy() {
        File(context.cacheDir, SHARED_FOLDER).deleteRecursively()
    }

    init {
        (audio as? AndroidAudioCapture)?.onInterruption = { active ->
            interruptedBySystem = active
            if (active) replies.callChanged(began = true)
            pipeline.systemInterruptionChanged(active)
            if (!active) replies.callChanged(began = false)
            publish()
        }
        pipeline.onPhaseChange = {
            captionsBusy.value = it.isListening || it.isTransitioning
            conversations.listeningChanged()
            replies.captionsCameOn()
            publish()
            announceStopOrReturn()
        }
        pipeline.onPreparationProgress = { publish() }
        pipeline.onCaptionsChanged = ::publish
        pipeline.onInputsChanged = ::publishInputs
        pipeline.onKeywordHits = ::wordsHeard
        pipeline.onSoundAlert = ::soundHeard
        replies.onSpeaking = CaptionState::speaking
        pipeline.onEvent = { journal.append(it.description, it.at) }
        val started = settings.current.value
        started.speakerProfiles.forEach(pipeline::enroll)
        note("APP STARTED: ${appVersion()} Android ${Build.VERSION.RELEASE} engine ${started.engine.rawValue} model ${started.modelDescription(started.engine) ?: "-"} ${deviceState()}")
        scope.launch { combine(settings.current, settings.saveFailed) { _, _ -> }.collect { refreshSavingTrouble() } }
        context.registerReceiver(batteryReadings, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        context.registerReceiver(batterySaving, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) watchHeat()
        loadRecentConversation()
    }

    /** Android tells a new listener the heat as it is; only a change is noted. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun watchHeat() {
        val now = power.currentThermalStatus
        heatNow = deviceHeat(now)
        val listener = HeatListener(now) { status ->
            heatNow = deviceHeat(status)
            note("heat: ${deviceState()}")
        }
        power.addThermalStatusListener(context.mainExecutor, listener)
        heat = listener
    }

    /**
     * Looks for a conversation cut off moments ago, as the iPhone does as
     * it opens: Android can close the app mid-conversation too. Found
     * after she took the offer away or another was put up meanwhile, it
     * is dropped.
     */
    fun loadRecentConversation(now: Double = System.currentTimeMillis() / 1_000.0) {
        val before = CaptionState.screen.value.recentConversation
        scope.launch {
            val summaries = withContext(Dispatchers.IO) { historyStore.summariesModifiedSince(RecentConversation.oldestQualifyingSave(now)) }
            val found = RecentConversation.resumable(summaries.filterNot { conversations.isCurrent(it.id) }, now)
            if (CaptionState.screen.value.recentConversation == before) CaptionState.recentConversation(found)
        }
    }

    fun dismissRecentConversation() = CaptionState.recentConversation(null)

    internal val modelDirectory: File get() = modelFolder

    internal fun captionsActive(): Boolean = pipeline.phase.isListening || pipeline.phase.isTransitioning

    internal fun stopCaptionsForRecording() {
        replies.userTookControl()
        pipeline.stopFromNotification()
        pipeline.releaseModels()
    }

    internal suspend fun releaseModelForRecording() = withContext(Dispatchers.Main.immediate) { pipeline.releaseModels() }

    /** A transcribed recording goes into History like a conversation, unless the reader has turned saving off. */
    internal fun saveRecording(record: TranscriptSessionRecord): Boolean {
        if (!settings.current.value.saveHistory) return false
        return conversations.saveRecording(record)
    }

    fun listen() {
        replies.userTookControl()
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

    fun pause() {
        replies.userTookControl()
        pipeline.pause()
    }

    fun stopFromNotification() {
        replies.userTookControl()
        pipeline.stopFromNotification()
    }

    /** Settings' "Clear captions from the screen": the conversation so far is saved first. */
    fun clearCaptions() {
        dismissRecentConversation()
        conversations.clear(pipeline::clearTranscript)
        publish()
    }

    /**
     * Settings' "Show the initial walkthrough again", as on the iPhone:
     * captions stop (the conversation so far is kept) rather than keep the
     * microphone open behind a screen that isn't showing them, and the
     * walkthrough's end starts them again, as on the first run.
     */
    fun showWalkthroughAgain() {
        if (pipeline.phase != PipelinePhase.Idle) {
            replies.userTookControl()
            pipeline.stop()
        }
        settings.change { it.hasCompletedOnboarding = false }
        launchHandled = false
    }

    val isSpeaking: Boolean get() = replies.isSpeaking

    fun canAskToRepeat() = replies.canSay(SpokenReplies.REPEAT_REQUEST)

    fun askToRepeat() {
        replies.askToRepeat(settings.current.value.speechRate)
    }

    fun canSay(text: String) = replies.canSay(text)

    /** Back from the phone's settings a voice may have been added or removed, as the iPhone looks again. */
    fun refreshVoice() = voice.refreshVoice()

    fun speak(text: String) = replies.speak(text, settings.current.value.speechRate)

    fun stopSpeaking() = replies.stop()

    fun addQuickPhrase(phrase: String) {
        settings.change { it.quickPhrases = QuickPhrases.adding(phrase, it.quickPhrases, Localization.language) }
    }

    fun removeQuickPhrase(phrase: String) {
        settings.change { it.quickPhrases = QuickPhrases.removing(phrase, it.quickPhrases, Localization.language) }
    }

    fun moveQuickPhrase(phrase: String, by: Int) {
        settings.change { it.quickPhrases = QuickPhrases.moving(phrase, by, it.quickPhrases, Localization.language) }
    }

    fun restoreQuickPhrases() {
        settings.change { it.quickPhrases = AppSettings.defaultQuickPhrases(Localization.language) }
    }

    fun memoryShort(footprintBytes: Long) {
        pipeline.handleMemoryWarning(footprintBytes)
        conversations.memoryShort()
    }

    fun close() {
        runCatching { context.unregisterReceiver(batteryReadings) }
        runCatching { context.unregisterReceiver(batterySaving) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (heat as? HeatListener)?.let {
                it.changed = null
                power.removeThermalStatusListener(it)
            }
        }
        pipeline.close()
        network.close()
        voice.shutdown()
        scope.cancel()
    }

    fun settingsChanged() {
        scope.launch {
            when (pipeline.phase) {
                PipelinePhase.Idle -> Unit
                PipelinePhase.Paused -> pipeline.settingsChangedWhilePaused()
                else -> pipeline.restart(settings.current.value)
            }
        }
    }

    /**
     * Posts or takes away the "captions stopped" notice, as the iPhone does:
     * with the phone put away, a stop nothing will undo also stops the
     * alerts, and a dark screen says nothing of it. Android has no word of
     * a call that ended without giving the microphone back.
     */
    private fun checkCaptionsStillRunning() {
        val cause = StoppedCaptionsNotice.cause(pipeline.phase, pipeline.isRecoveringByItself, interruptedBySystem, callEndedDuringInterruption = false)
        when (val update = stoppedCaptions.update(cause, appIsActive, settings.current.value.notifyWhenInBackground)) {
            is StoppedCaptionsNotice.Update.Post -> if (!notifier.post(update.content)) stoppedCaptions.postFailed()
            is StoppedCaptionsNotice.Update.Withdraw -> notifier.withdraw(update.identifier)
            null -> Unit
        }
    }

    private fun publish() {
        checkCaptionsStillRunning()
        val transcribing = pipeline.activeSettings.takeIf { pipeline.isCoveringForCloud } ?: settings.current.value
        CaptionState.show(
            phase = pipeline.phase,
            engine = pipeline.activeEngineKind,
            interruptedBySystem = interruptedBySystem,
            scheduledRetry = pipeline.scheduledRetry,
            segments = pipeline.segments,
            listeningStartedAt = pipeline.listeningStartedAt,
            scoringEngine = transcribing.engine,
            scoringModel = transcribing.whisperModelVariant,
            starred = conversations::isStarred,
            conversation = conversations::savedConversation,
            keywordHits = pipeline.keywordHitSegmentIDs,
            recentlyHeard = pipeline.keywordHits,
            languageCode = transcribing.languageCode,
            speakerName = pipeline::displayName,
            downloadSecondsRemaining = pipeline.downloadSecondsRemaining,
            pausedForSpeech = replies.holdsCaptions && pipeline.phase == PipelinePhase.Paused,
            coveringForCloud = pipeline.isCoveringForCloud,
            coveredEngine = settings.current.value.engine,
            coverReason = pipeline.coverReason,
            offerBackup = offersBackup(),
        )
        refreshSavingTrouble()
        announceNewLines(transcribing)
        if (batteryWatch.listeningChanged(microphoneOn)) context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::readBattery)
    }

    /**
     * Captions that stopped by themselves, and their return, said to
     * TalkBack as on the iPhone: a reader who can't see the status line
     * would keep waiting for words that never come.
     */
    private fun announceStopOrReturn() {
        val phase = pipeline.phase
        val text = when (stopAnnouncer.phaseChanged(phase, appIsActive)) {
            CaptionsStopAnnouncer.Event.Stopped -> stoppedText(phase)
            CaptionsStopAnnouncer.Event.Back -> tr("הכתוביות חזרו", "Captions are back")
            null -> return
        }
        if (appIsActive && ScreenReader.isOn(context)) ScreenReader.announce(context, text)
    }

    private fun stoppedText(phase: PipelinePhase): String {
        val status = PhasePresentation(phase, pipeline.activeEngineKind, interruptedBySystem, pipeline.scheduledRetry)
        return tr("הכתוביות נעצרו: %1", "Captions stopped: %1", listOf(status.title))
    }

    private val microphoneOn: Boolean get() = BatteryAdvisor.watches(pipeline.phase)

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryWatch.reading(
            level = level.takeIf { it >= 0 && scale > 0 }?.let { it.toFloat() / scale },
            pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            listening = microphoneOn,
        )
    }

    /** On screen the banner says so with a buzz; with the app away it becomes a notification, as on the iPhone. */
    fun batteryWarningRaised(warning: BatteryWarning) {
        if (appIsActive) {
            buzz(BATTERY_BUZZ)
        } else if (settings.current.value.notifyWhenInBackground) {
            notifier.post(warning.notificationContent)
        }
    }

    fun dismissBatteryWarning() = CaptionState.battery(null)

    /**
     * Finished lines reach TalkBack (speech or a braille display) by
     * themselves while the captions are in front, as VoiceOver gets them
     * on the iPhone. Lines that finish while it is off, or the setting is,
     * are skipped for good, so turning either on later doesn't read out
     * the backlog.
     */
    private fun announceNewLines(transcribing: AppSettings) {
        val segments = pipeline.segments
        val display = settings.current.value.display
        if (!appIsActive || !display.announceNewLines || !ScreenReader.isOn(context)) {
            announcer.skipLinesSoFar(segments)
            return
        }
        val text = announcer.announcement(
            segments,
            speakerName = { if (display.showSpeakerNames && it.speakerClusterID != null) pipeline.displayName(it) else null },
            isUncertain = { display.markUncertainLines && CaptionConfidence.isUncertain(it, transcribing.engine, transcribing.whisperModelVariant) },
        ) ?: return
        ScreenReader.announce(context, text)
    }

    /**
     * Her choice in the picker, kept for next time even when the phone
     * refuses to switch now; false when it refused.
     */
    fun selectInput(uid: String): Boolean {
        val switched = pipeline.selectInput(uid)
        settings.change { it.preferredInputUID = uid }
        return switched
    }

    /** Asks the phone again which microphones there are: "I just plugged it in". */
    fun refreshInputs() = pipeline.refreshInputs()

    fun dismissMicrophoneDrop() = pipeline.dismissMicrophoneDrop()

    /** The Settings switch: models may download over cellular data, and one waiting for Wi-Fi starts now. */
    fun allowCellularModelDownload(allowed: Boolean) {
        settings.change { it.allowCellularModelDownload = allowed }
        scope.launch { pipeline.setAllowCellularModelDownload(allowed) }
    }

    /** "Download now" while the model waits for Wi-Fi: this session's model may use cellular data. */
    fun approveCellularDownload() {
        scope.launch { pipeline.approveCellularDownload() }
    }

    private fun publishInputs() = CaptionState.inputs(pipeline.availableInputs, pipeline.selectedInputUID, pipeline.microphoneDrop.lost)

    /** What the diagnostics screen shows and its report says, as things stand now. */
    override fun readJournal() = DiagnosticsJournal(journal.reportLines(::utcOffsetAt), journal.entries().any { ProblemSnapshot.isCaptionLine(it.text) })

    override fun diagnostics(journal: DiagnosticsJournal?): DiagnosticsFacts {
        val read = journal ?: readJournal()
        val power = context.getSystemService(PowerManager::class.java)
        // The sticky broadcast, not BatteryManager's properties: it is what
        // Android itself shows, test overrides included.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return DiagnosticsFacts(
            phase = pipeline.phase,
            activeEngine = pipeline.activeEngineKind,
            settings = settings.current.value.copy(),
            showing = Localization.language,
            homeServerCodeSaved = homeServerCode.read() != null,
            selectedInput = pipeline.availableInputs.firstOrNull { it.uid == pipeline.selectedInputUID },
            availableInputs = pipeline.availableInputs,
            stats = pipeline.stats,
            linesOnScreen = pipeline.segments.size,
            scheduledRetry = pipeline.scheduledRetry,
            interruptedBySystem = interruptedBySystem,
            settingsSaveFailed = settings.saveFailed.value,
            historySaveFailure = conversations.lastSaveFailure,
            events = pipeline.eventLog.reportLines(::utcOffsetAt),
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            appVersion = appVersion(),
            journal = read.lines,
            hasMarkedCaptionLines = read.hasMarkedCaptionLines,
            notificationsAllowed = AlertNotifier.allowed(context),
            canVibrate = phoneVibrator(context)?.hasVibrator() == true,
            network = pipeline.networkConditions,
            modelInstalled = modelFile(settings.current.value).exists(),
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null,
            batterySaver = power.isPowerSaveMode,
            batteryPercent = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }?.takeIf { it >= 0 },
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            freeBytes = context.filesDir.usableSpace,
            memoryInUseBytes = Debug.getPss() * 1_024,
            memoryAvailableBytes = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem,
            problemClips = problemAudio.clips(),
            inputLevel = pipeline.inputLevel,
            speakersIdentified = pipeline.speakerClusters.size,
            soundNearMisses = pipeline.soundNearMisses.copy(),
            soundAlertConfidence = pipeline.soundAlertConfidence,
            coveringForCloud = pipeline.isCoveringForCloud,
            lockScreenAllowed = ListeningService.shown(context),
            lockScreenShowing = ListeningService.showingOnLockScreen,
        )
    }

    override fun markProblem(): Boolean {
        val now = System.currentTimeMillis() / 1_000.0
        val lines = ProblemSnapshot.lines(
            settings = settings.current.value,
            activeEngine = pipeline.activeEngineKind,
            input = pipeline.availableInputs.firstOrNull { it.uid == pipeline.selectedInputUID },
            stats = pipeline.stats,
            segments = pipeline.segments,
            device = deviceState(),
            utcOffsetSeconds = utcOffsetAt(now),
            includeCaptions = settings.current.value.saveHistory,
        )
        lines.forEach { journal.append(it, now) }
        // With saving conversations off, the room's sound isn't kept either.
        val clip = if (settings.current.value.saveHistory) problemAudio.save(pipeline.recentAudioSamples, SAMPLE_RATE, Instant.ofEpochMilli((now * 1_000).toLong())) else null
        clip?.let { journal.append("  sound saved: ${it.name}", now) }
        scope.launch { pipeline.engineDiagnostics()?.let { journal.append("  engine: $it", now) } }
        return clip != null
    }

    override fun deleteMarkedCaptionLines() = journal.removeEntries(ProblemSnapshot::isCaptionLine)

    override fun deleteProblemClip(clip: File) = problemAudio.remove(clip)

    override suspend fun sendReportHome(text: String) = reportSender(text)

    /** Where a report sent home goes; device tests put in one that says it was saved. */
    var reportSender: suspend (String) -> Boolean = { text ->
        settings.current.value.let { homeComputerEngine(it).sendReport(text, it.languageCode) }
    }

    private fun note(text: String) = journal.append(text, System.currentTimeMillis() / 1_000.0)

    private fun appVersion(): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${app.versionName} (${PackageInfoCompat.getLongVersionCode(app)})"
    }

    /** Heat, Battery Saver and the memory the app holds: all slow captions down with nothing on screen saying so. */
    private fun deviceState(): String {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${power.currentThermalStatus}" else "-"
        return "thermal $thermal low power ${power.isPowerSaveMode} memory ${Debug.getPss() / 1_024}MB"
    }

    private fun utcOffsetAt(at: Double): Int = TimeZone.getDefault().getOffset((at * 1_000).toLong()) / 1_000

    /** Hides the saving-failed banner until saving works and then fails again. */
    fun dismissSavingTrouble() {
        savingTrouble.dismiss()
        refreshSavingTrouble()
    }

    private fun refreshSavingTrouble() {
        savingTrouble.update(
            settingsFailed = settings.saveFailed.value,
            historyFailed = settings.current.value.saveHistory && conversations.lastSaveFailure != null,
        )
        CaptionState.savingTrouble(savingTrouble.shouldShow)
    }

    /** What was said since the last autosave is written first, so a conversation opened from the captions has every line. */
    fun saveBeforeReading() {
        conversations.save(ended = false)
    }

    /** Her name, typed: it buzzes when said, and goes first on the names list so every engine expects it, as on the iPhone. */
    fun addNameAlert(name: String) {
        addAlert(name)
        addWord(name)
    }

    fun addAlert(phrase: String) = setImportantWords(ImportantWords.adding(phrase, settings.current.value.keywordAlerts))

    fun dismissNameAlertOffer() = settings.change { it.nameAlertOfferDismissed = true }

    /** A word the captions got wrong goes first in the word list. */
    fun addWord(word: String) = setWords(listOf(word) + settings.current.value.vocabulary)

    /** The words that buzz when said; a running engine listens for them from the next line. */
    fun setImportantWords(alerts: List<KeywordAlert>) {
        settings.change { it.keywordAlerts = alerts }
        pipeline.setKeywordAlerts(alerts)
    }

    /** Which sounds alert, and from how faint; a running pipeline judges the next sound by them. */
    fun setSoundAlerts(preferences: SoundAlertPreferences) {
        settings.change { it.soundAlerts = preferences }
        pipeline.setSoundAlertPreferences(preferences)
    }

    fun soundNearMiss(event: SoundEvent) = pipeline.soundNearMisses.entry(event)

    /** The saved people, each once however many voice prints they have, for the naming dialog's one-tap list. */
    val savedSpeakerNames: List<String> get() = SavedSpeaker.grouping(settings.current.value.speakerProfiles).map { it.name }

    /**
     * "Who is speaking?" on a line, as on the iPhone: its voice shows
     * [name] from now on, and the lines on screen and their saved
     * conversations follow. A saved person's voice given a new name is a
     * spelling fixed, everywhere; the name of another saved person means
     * the phone took this voice for the wrong one, so it gets a print of
     * its own instead of taking that person's.
     */
    fun nameSpeaker(lineID: UUID, name: String) {
        val trimmed = name.trim()
        val line = pipeline.segments.firstOrNull { it.id == lineID } ?: return
        if (trimmed.isEmpty()) return
        val oldName = line.speakerClusterID?.let { voice -> pipeline.speakerClusters.firstOrNull { it.id == voice }?.name }
        val print = pipeline.nameSpeaker(line, trimmed) ?: return
        when (val naming = SpeakerNaming.of(oldName, trimmed, settings.current.value.speakerProfiles.map { it.name }.toSet())) {
            is SpeakerNaming.Rename -> renamePerson(naming.from, trimmed)
            SpeakerNaming.Unchanged -> Unit
            SpeakerNaming.NewPrint -> {
                settings.change { it.speakerProfiles = it.speakerProfiles + SpeakerProfile(name = trimmed, embedding = print) }
                speakerLabelsChanged()
            }
        }
    }

    private fun renamePerson(from: String, to: String) {
        settings.change { current -> current.speakerProfiles = current.speakerProfiles.map { if (it.name == from) it.copy(name = to) else it } }
        val words = settings.current.value.vocabulary
        if (from in words) setWords(words.map { if (it == from) to else it })
        pipeline.renameSpeakers(from, to)
        speakerLabelsChanged()
        conversations.renameSpeaker(from, to)
    }

    private fun speakerLabelsChanged() {
        conversations.speakerLabelsChanged()
        publish()
    }

    override val inputLevel: Float get() = pipeline.inputLevel

    override fun inputLevelNow(): Float = pipeline.inputLevel

    override fun needsNewRecording(speaker: SavedSpeaker): Boolean {
        val prints = settings.current.value.speakerProfiles.filter { it.id in speaker.profileIDs }
        return prints.isNotEmpty() && prints.none(pipeline::canRecognize)
    }

    override fun renameSpeaker(name: String, to: String) {
        val trimmed = to.trim()
        if (trimmed.isEmpty() || trimmed == name || settings.current.value.speakerProfiles.none { it.name == name }) return
        renamePerson(name, trimmed)
    }

    override fun removeSpeaker(name: String) {
        if (settings.current.value.speakerProfiles.none { it.name == name }) return
        settings.change { current -> current.speakerProfiles = current.speakerProfiles.filter { it.name != name } }
        pipeline.forgetSpeakerName(name)
        speakerLabelsChanged()
    }

    /** Running captions judge the next voice by it; every later start reads it from the settings. */
    override fun setSpeakerSeparation(threshold: Float) {
        settings.change { it.speakerSimilarityThreshold = threshold }
        pipeline.setSpeakerSimilarityThreshold(threshold)
    }

    /**
     * As on the iPhone, through the captions' own microphone and format,
     * which the voice prints of live speech are compared in. A recording
     * stopped midway is not kept as the person's voice.
     */
    override suspend fun recordSpeaker(name: String, seconds: Double, onProgress: (Double) -> Unit): Boolean {
        val samples = pipeline.captureEnrollmentSamples(seconds, onProgress)
        if (!currentCoroutineContext().isActive) return false
        val print = pipeline.embeddingInBackground(samples) ?: return false
        save(name, print)
        return true
    }

    /**
     * Separate prints per recording, not one average, as on the iPhone: on
     * the Hebrew speaker bench three separate short prints named the right
     * person 84% of the time against 71% for the same audio averaged.
     */
    override suspend fun addRecordings(recordings: List<Uri>): RecordingImport.Result {
        val added = LinkedHashMap<String, Int>()
        val unusable = mutableListOf<String>()
        for (recording in recordings) {
            val fileName = fileName(recording)
            val name = RecordingImport.personName(fileName)
            val samples = name?.let { withContext(Dispatchers.IO) { AudioFileSamples.load(context, recording) } }
            val print = samples?.let { pipeline.embeddingInBackground(it) }
            if (name == null || print == null) {
                unusable += fileName
                continue
            }
            save(name, print)
            added[name] = (added[name] ?: 0) + 1
        }
        return RecordingImport.Result(added, unusable)
    }

    private fun fileName(recording: Uri): String =
        runCatching {
            context.contentResolver.query(recording, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: recording.lastPathSegment?.substringAfterLast('/') ?: recording.toString()

    private fun save(name: String, print: FloatArray) {
        val profile = SpeakerProfile(name = name, embedding = print)
        pipeline.enroll(profile)
        settings.change { it.speakerProfiles = it.speakerProfiles + profile }
    }

    /** The names and words the engines are given, in order; a running engine has them from the next sentence. */
    fun setWords(words: List<String>) {
        settings.change { it.vocabulary = VocabularyHints.normalized(words) }
        val terms = settings.current.value.vocabulary
        scope.launch { pipeline.setVocabulary(terms) }
    }

    /**
     * Important words just heard. As on the iPhone, they buzz and TalkBack
     * says them whichever screen is open, not only the captions; with the
     * app away they become a phone notification. One that went out that way
     * stays quiet as she comes back, and one that never did (alerts while
     * away off, quiet hours, the phone's notifications for Ozen off) buzzes
     * then, however long ago it was said.
     */
    fun wordsHeard(hits: List<KeywordHit>, line: TranscriptSegment) {
        val now = System.currentTimeMillis() / 1_000.0
        val utcOffsetSeconds = TimeZone.getDefault().getOffset((now * 1_000).toLong()) / 1_000
        val told = hits.filter { hit ->
            awayAlerts.forWords(listOf(hit), line.text, appIsActive, settings.current.value, now, utcOffsetSeconds).map { content ->
                val delivery = notifier.deliver(content)
                delivery.buzz?.let { pipeline.ignoreSounds(it) }
                delivery.posted
            }.any { it }
        }
        attention.claim(hits - told.toSet())?.let { word ->
            CaptionState.heard(word)
            if (appIsActive) tell(word) else untold = untold ?: word
        }
        publish()
    }

    /** A word heard while she was away that nothing has told her of yet. */
    private var untold: HeardWord? = null

    private fun tell(word: HeardWord) {
        buzz(AlertVibration.keyword)
        if (ScreenReader.isOn(context)) ScreenReader.announce(context, tr("נאמר: %1", "Said: %1", listOf(word.said)))
    }

    /**
     * A sound heard: with the app in front the phone buzzes and TalkBack
     * says it, as on the iPhone, whichever screen is open, and the caption
     * screen shows its banner; with the app away it is a notification.
     */
    private fun soundHeard(alert: SoundAlert) {
        CaptionState.soundHeard(pipeline.currentBannerSoundAlert, alert)
        if (appIsActive) {
            buzz(AlertVibration.pattern(alert.event.importance), alert.event.importance)
            val critical = alert.event.importance == SoundEvent.Importance.Critical
            val said = if (critical) tr("שימו לב! %1", "Attention! %1", listOf(alert.event.name)) else tr("התראה: %1", "Alert: %1", listOf(alert.event.name))
            if (ScreenReader.isOn(context)) ScreenReader.announce(context, said)
        }
        val now = System.currentTimeMillis() / 1_000.0
        val utcOffsetSeconds = TimeZone.getDefault().getOffset((now * 1_000).toLong()) / 1_000
        awayAlerts.forSound(alert, appIsActive, settings.current.value, now, utcOffsetSeconds)?.let { content ->
            notifier.deliver(content).buzz?.let { pipeline.ignoreSounds(it) }
        }
    }

    fun dismissSoundAlert(id: UUID) {
        pipeline.dismissSoundAlert(id)
        CaptionState.sound(pipeline.currentBannerSoundAlert)
    }

    fun soundBannerSecondsLeft(alert: SoundAlert) = pipeline.bannerSecondsLeft(alert)

    /** Buzzes with sounds that could be the buzz ignored meanwhile, so the phone on a table isn't heard ringing. */
    fun buzz(vibration: AlertVibration, importance: SoundEvent.Importance? = null) {
        pipeline.ignoreSounds(vibration)
        Buzz.play(context, vibration, importance)
    }

    /** Stars a caption line or takes its star away, in its conversation and on screen. */
    fun toggleStar(line: UUID) {
        CaptionState.markStarred(line, conversations.toggleLiveStar(line))
    }

    suspend fun checkHomeComputer(): HomeServerCheck {
        val current = settings.current.value
        val started = TimeSource.Monotonic.markNow()
        val availability = homeComputerEngine(current).checkAvailability(current.languageCode)
        return HomeServerCheck.of(availability, started.elapsedNow().toDouble(DurationUnit.SECONDS))
    }

    private fun homeComputerEngine(settings: AppSettings) = HomeServerEngine(
        address = settings.homeServerAddress,
        token = homeServerCode::read,
        connector = connector,
        client = "Ozen ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}, Android ${Build.VERSION.RELEASE}",
        beam = settings.homeServerBeam,
    )

    internal fun engine(settings: AppSettings): TranscriptionEngine = when (settings.engine) {
        TranscriptionEngineKind.HomeServer -> homeComputerEngine(settings)
        TranscriptionEngineKind.Cloud -> settings.cloudProvider.let { provider ->
            provider.engine(
                model = settings.chosenCloudModel,
                connector = connector,
                apiKey = { cloudKeys.read(provider) },
            )
        }
        TranscriptionEngineKind.WhisperKit, TranscriptionEngineKind.AppleSpeech -> modelFile(settings).let { model ->
            OnDeviceWhisperEngine(
                modelName = model.name,
                loader = ModelFileLoader(
                    model,
                    context.applicationInfo.nativeLibraryDir,
                    download = PhoneModelDownloader(model, settings.phoneModel.source, modelFetcher),
                    network = { networkNow() },
                    freeBytes = { freeSpace() },
                    variant = settings.phoneModel.variant,
                ),
                voiceScorerFactory = ::voiceScore,
                heat = { heatNow },
                lowPowerMode = { savingPower },
            )
        }
    }

    /** Where a missing model is fetched from; device tests put one in that never downloads. */
    var modelFetcher: ReleaseFileFetching = newModelFetcher()

    private val backupState = MutableStateFlow<BackupModelStatus>(BackupModelStatus.NotNeeded)
    override val backupStatus: StateFlow<BackupModelStatus> = backupState

    // As on the iPhone: the language whose model the download fetches, so
    // another language picked meanwhile shows neither its progress nor its
    // failure.
    private var backupProgress: Double? = null
    private var backupLanguage: UILanguage? = null
    private var backupFailedLanguage: UILanguage? = null

    override fun refreshBackup() {
        val current = settings.current.value
        val language = current.spokenLanguage
        val source = current.phoneModel.source
        val model = modelFile(current)
        backupState.value = run {
            BackupModel.status(
                engine = current.engine,
                installed = model.isFile,
                sizeMegabytes = ((source.bytes + 999_999) / 1_000_000).toInt(),
                downloading = backupProgress.takeIf { backupLanguage == language },
                failed = backupFailedLanguage == language,
                shortfallMegabytes = StorageSpaceGate.shortfallMegabytes(PhoneModelDownloader(model, source, modelFetcher).pendingMegabytes() ?: 0, freeSpace()),
                network = pipeline.networkConditions,
                allowCellular = current.allowCellularModelDownload,
            )
        }
    }

    /** As on the iPhone: with the computer out of reach and nothing on the phone to carry on, the status points to the backup. */
    private fun offersBackup(): Boolean {
        val unreachable = (pipeline.phase as? PipelinePhase.Failed)?.reason?.engineUnavailability?.kind
        if (unreachable != EngineUnavailability.Kind.HomeServerUnreachable) return false
        refreshBackup()
        return BackupModel.shouldOffer(unreachable, backupState.value)
    }

    override fun downloadBackup() {
        refreshBackup()
        if (!BackupModel.canStart(backupState.value)) return
        val current = settings.current.value
        val language = current.spokenLanguage
        val download = PhoneModelDownloader(modelFile(current), current.phoneModel.source, modelFetcher)
        backupFailedLanguage = null
        backupProgress = 0.0
        backupLanguage = language
        refreshBackup()
        var stoppedForWiFi = false
        scope.launch {
            val done = withContext(Dispatchers.IO) {
                runCatching {
                    download.download { fraction ->
                        // As the captions' own download: started on Wi-Fi, it
                        // stops when only the phone plan is left and cellular
                        // downloads are off, keeping what came. Not a failure:
                        // the row waits for Wi-Fi and offers it again after.
                        if (ModelDownloadGate.decide(networkNow(), settings.current.value.allowCellularModelDownload) == ModelDownloadGate.Decision.WaitForWiFi) {
                            stoppedForWiFi = true
                            throw IOException("Wi-Fi went, and downloads on cellular data are off")
                        }
                        // Whole percents, all the row shows: every chunk
                        // off the network would redraw Settings otherwise.
                        val shown = (fraction * 100).toInt() / 100.0
                        scope.launch {
                            if (backupLanguage == language && backupProgress != null && shown != backupProgress) {
                                backupProgress = shown
                                refreshBackup()
                            }
                        }
                    }
                }.isSuccess
            }
            if (backupLanguage == language) {
                backupProgress = null
                backupLanguage = null
            }
            if (!done && !stoppedForWiFi) backupFailedLanguage = language
            refreshBackup()
        }
    }

    /** The phone's own model: the chosen one, or the spoken language's recommended one; see `PhoneModelCatalog`. */
    private fun modelFile(settings: AppSettings) = File(modelFolder, settings.phoneModel.file)

    val phoneModels: PhoneModels by lazy {
        PhoneModelChooser(
            settings = settings,
            modelFolder = modelFolder,
            fetcher = { modelFetcher },
            network = { networkNow() },
            freeSpace = { freeSpace() },
            scope = scope,
            measure = { progress -> PhoneSpeedMeter(context.applicationInfo.nativeLibraryDir).run(progress) },
            applied = ::settingsChanged,
            captionsBusy = captionsBusy,
            totalMemory = { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.totalMem.takeIf { it > 0 } },
        )
    }

    private val captionsBusy = MutableStateFlow(false)

    private fun voiceScore(): ((FloatArray) -> Float?)? {
        val scorer = voiceScorer ?: return null
        scorer.reset()
        return { input -> scorer.score(input) }
    }

    companion object {
        /**
         * What each new session fetches models with. The device tests' runner
         * swaps in one that never downloads: once the Hebrew model could be
         * downloaded, every test session started in an empty folder pulled
         * the real 574 MB file.
         */
        var newModelFetcher: () -> ReleaseFileFetching = { HTTPFileFetcher() }
        val MODEL_FILE = WhisperModelCatalog.phoneModelFile(UILanguage.Hebrew)
        const val HISTORY_FOLDER = "ozen-history"
        const val JOURNAL_FILE = "ozen-journal.log"
        const val PROBLEM_AUDIO_FOLDER = "problem-audio"
        private const val SAMPLE_RATE = 16_000

        /** The iPhone's warning buzz: two taps, the second stronger. */
        val BATTERY_BUZZ = AlertVibration(listOf(AlertVibration.Pulse(0.0, 0.1, 0.6f, 0.5f), AlertVibration.Pulse(0.2, 0.15, 1f, 0.5f)))

        /** In the cache: the conversation last shared as a file, kept for the app it went to. */
        const val SHARED_FOLDER = "shared"
    }
}

/**
 * Android's thermal service keeps hold of a listener until its own memory
 * is collected, long after it was removed: only this goes with it, not the
 * session it reported to.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class HeatListener(private var last: Int, var changed: ((Int) -> Unit)?) : PowerManager.OnThermalStatusChangedListener {
    override fun onThermalStatusChanged(status: Int) {
        if (status != last) changed?.invoke(status)
        last = status
    }
}

/**
 * Android's seven heat levels in the iPhone's four (`ProcessInfo.ThermalState`):
 * light and moderate are the phone held back a little, which the passes'
 * own slowing already follows; severe on is held back hard, where the
 * iPhone spaces its live passes out. A level unknown here counts as hot,
 * as the iPhone's does.
 */
internal fun deviceHeat(thermalStatus: Int): DeviceHeat = when (thermalStatus) {
    PowerManager.THERMAL_STATUS_NONE -> DeviceHeat.Nominal
    PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> DeviceHeat.Fair
    PowerManager.THERMAL_STATUS_SEVERE -> DeviceHeat.Serious
    else -> if (thermalStatus > PowerManager.THERMAL_STATUS_SEVERE) DeviceHeat.Critical else DeviceHeat.Serious
}
