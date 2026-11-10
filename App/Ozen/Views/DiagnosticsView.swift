import SwiftUI
import UIKit
import OzenKit
import OzenPlatform

/// The numbers behind "it went quiet". Every counter the pipeline keeps,
/// plus device/app facts, with one button that copies it all as text so
/// it can be pasted into a message when asking for help.
struct DiagnosticsView: View {
    let viewModel: LiveCaptionViewModel
    @State private var copied = false
    /// Read once, and again after a mark: reading waits for the file.
    @State private var journalLines: [String] = []
    @State private var hasMarkedLines = false
    @State private var problemClips: [URL] = []
    /// Built once on appearing, and again right before it's actually sent
    /// or copied, rather than on every body evaluation: this screen also
    /// shows a live `inputLevel` meter that updates ~20 times a second
    /// while listening, and `report` joins hundreds of lines from
    /// `eventLog` and the journal, which is wasted work to redo on each of
    /// those ticks when nobody has asked to see the report itself.
    @State private var reportText = ""
    @State private var reportSentHome: Bool?
    @State private var sendingHome = false
    /// Whether iOS lets Ozen show notifications at all. The last send's
    /// own result can't say: with permission denied iOS drops every
    /// notification without reporting an error, so it read "OK" while no
    /// background alert was ever shown.
    @State private var notificationsAllowed: Bool?
    @State private var builtInRecognition: String?

    private static func clipTitle(_ clip: URL) -> String {
        let stamp = clip.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "problem-", with: "")
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd-HHmmss"
        guard let date = parser.date(from: stamp) else { return stamp }
        return date.formatted(inAppLanguage: .abbreviated, time: .standard)
    }

    private func reloadJournal() {
        journalLines = viewModel.journal?.reportLines(utcOffsetAt: DiagnosticsReport.utcOffset(at:)) ?? []
        hasMarkedLines = viewModel.hasMarkedCaptionLines
    }

    var body: some View {
        Form {
            Section {
                ShareLink(item: reportText, subject: Text(tr("דוח אבחון של אוזן", "Ozen diagnostics report"))) {
                    Label(tr("שליחת הדוח", "Send report"), systemImage: "square.and.arrow.up")
                }
                Button {
                    reportText = report
                    UIPasteboard.general.string = reportText
                    copied = true
                } label: {
                    Label(copied ? tr("הועתק", "Copied") : tr("העתקת הדוח", "Copy report"), systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                if viewModel.canSendReportToHomeServer {
                    Button {
                        reportText = report
                        sendingHome = true
                        Task {
                            reportSentHome = await viewModel.sendReportToHomeServer(reportText)
                            sendingHome = false
                        }
                    } label: {
                        switch reportSentHome {
                        case true?:
                            Label(tr("הדוח נשמר במחשב בבית", "Saved on the home computer"), systemImage: "checkmark")
                        case false?:
                            Label(tr("המחשב בבית לא קיבל את הדוח. נסו שוב", "The home computer didn’t get it. Try again"), systemImage: "exclamationmark.triangle")
                        case nil:
                            Label(tr("שליחת הדוח למחשב בבית", "Send the report to the home computer"), systemImage: "desktopcomputer")
                        }
                    }
                    .disabled(sendingHome)
                }
            }

            Section(tr("מצב", "Status")) {
                DiagnosticsRow(tr("שלב", "Stage"), DiagnosticsReport.describe(viewModel.phase))
                DiagnosticsRow(tr("מנוע פעיל", "Active engine"), viewModel.pipeline.activeEngineKind?.displayName ?? "—")
                DiagnosticsRow(tr("מודל Whisper", "Whisper model"), identifier: "diagnosticsWhisperModel", viewModel.settings.whisperModelVariant)
                DiagnosticsRow(tr("שפה", "Language"), viewModel.settings.languageCode)
                if let failure = viewModel.phase.failure {
                    LabeledContent(tr("פרטי תקלה", "Failure details")) {
                        Text(failure.detail)
                            .font(.caption)
                            .multilineTextAlignment(.leading)
                    }
                }
            }

            Section(tr("אודיו", "Audio")) {
                DiagnosticsRow(tr("מיקרופון נבחר", "Selected microphone"), viewModel.selectedInput?.portName ?? "—")
                DiagnosticsRow(tr("מיקרופונים זמינים", "Available microphones"), "\(viewModel.availableInputs.count)")
                ForEach(viewModel.availableInputs) { input in
                    Text("\(input.portName) · \(MicPickerView.typeName(for: input.portType))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                DiagnosticsRow(tr("עוצמה עכשיו", "Level now"), String(format: "%.0f%%", viewModel.inputLevel * 100))
                DiagnosticsRow(tr("חבילות אודיו", "Audio chunks"), "\(viewModel.stats.audioChunksReceived)")
                DiagnosticsRow(tr("שניות אודיו", "Audio seconds"), String(format: "%.1f", viewModel.stats.audioSecondsReceived))
                DiagnosticsRow(tr("החלפות מיקרופון", "Microphone changes"), "\(viewModel.stats.inputChanges)")
                DiagnosticsRow(tr("תקיעות מיקרופון", "Microphone stalls"), "\(viewModel.stats.audioStalls)")
                DiagnosticsRow(tr("רמות קול (dBFS)", "Sound levels (dBFS)"), Self.levelsText(viewModel.stats.inputLevels))
                DiagnosticsRow(tr("נשמע כדיבור", "Sounded like speech"), viewModel.stats.speechShare.map { String(format: "%.0f%%", $0 * 100) } ?? "—")
                DiagnosticsRow(tr("זיהוי צלילים", "Sound detection"), viewModel.stats.soundDetectionRunning ? tr("פועל", "Running") : (viewModel.isListening ? tr("נעצר", "Stopped") : "—"))
            }

            nearMissesSection

            Section(tr("תמלול", "Transcription")) {
                DiagnosticsRow(tr("עדכונים מהמנוע", "Updates from engine"), "\(viewModel.stats.tokensReceived)")
                DiagnosticsRow(tr("שורות שנסגרו", "Lines closed"), "\(viewModel.stats.segmentsCommitted)")
                DiagnosticsRow(tr("שורות על המסך", "Lines on screen"), "\(viewModel.segments.count)")
                DiagnosticsRow(tr("עיכוב בכתוביות", "Caption lag"), viewModel.stats.captionLagSeconds.map { String(format: tr("%.1f שנ׳", "%.1f s"), $0) } ?? "—")
                DiagnosticsRow(tr("הפעלות מחדש", "Restarts"), "\(viewModel.stats.engineRestarts)")
                DiagnosticsRow(tr("דוברים שזוהו", "Speakers identified"), "\(viewModel.pipeline.speakerClusters.count)")
                DiagnosticsRow(tr("דוברים חדשים מאז שהאפליקציה נפתחה", "New speakers since the app opened"), "\(viewModel.stats.speakerClustersOpened)")
                if let started = viewModel.stats.sessionStartedAt {
                    DiagnosticsRow(tr("ההאזנה התחילה", "Listening started"), Date(timeIntervalSince1970: started).formatted(inAppLanguage: .omitted, time: .standard))
                }
            }

            Section(tr("התאוששות", "Recovery")) {
                DiagnosticsRow(tr("ניסיון חוזר אוטומטי", "Automatic retry"), retryText)
                DiagnosticsRow(tr("שיחת טלפון תופסת את האודיו", "Phone call is using the audio"), viewModel.isInterruptedBySystem ? tr("כן", "Yes") : tr("לא", "No"))
                DiagnosticsRow(tr("שמירת הגדרות", "Settings save"), viewModel.settingsSaveError == nil ? tr("תקינה", "Working") : tr("נכשלה", "Failed"))
                DiagnosticsRow(tr("שמירת שיחות", "Saving conversations"), viewModel.historySaveFailure == nil ? tr("תקינה", "Working") : tr("נכשלה", "Failed"))
                DiagnosticsRow(tr("התראות בטלפון", "Phone notifications"), notificationsText)
                DiagnosticsRow(tr("רטט להתראות", "Alert vibration"), Self.vibrationText)
                DiagnosticsRow(tr("חיבור לאינטרנט", "Internet connection"), DiagnosticsReport.describe(viewModel.pipeline.networkConditions))
            }

            Section {
                let lines = viewModel.pipeline.eventLog.reportLines(utcOffsetAt: DiagnosticsReport.utcOffset(at:))
                if lines.isEmpty {
                    Text(tr("עוד לא קרה כלום", "Nothing has happened yet"))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(lines.suffix(12).reversed().enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.caption.monospaced())
                            .environment(\.layoutDirection, .leftToRight)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } header: {
                Text(tr("אירועים אחרונים", "Recent events"))
            } footer: {
                Text(tr("החדשים ביותר למעלה. הדוח המועתק כולל את הרשימה המלאה.", "Newest at the top. The copied report includes the full list."))
            }

            Section {
                Button {
                    viewModel.markProblem()
                    reloadJournal()
                    problemClips = viewModel.problemAudio?.clips() ?? []
                    reportText = report
                } label: {
                    Label(tr("לסמן בעיה עכשיו", "Mark a problem now"), systemImage: "exclamationmark.bubble")
                }
                ForEach(Array(journalLines.suffix(15).reversed().enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.caption.monospaced())
                        .environment(\.layoutDirection, .leftToRight)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if hasMarkedLines {
                    Button(role: .destructive) {
                        viewModel.deleteMarkedCaptionLines()
                        reloadJournal()
                        reportText = report
                    } label: {
                        Label(tr("מחיקת שורות הכתוביות שנשמרו כאן", "Delete the caption lines kept here"), systemImage: "trash")
                    }
                }
            } header: {
                Text(tr("יומן, כולל הפעלות קודמות", "Journal, previous runs included"))
            } footer: {
                Text(tr("נשמר בטלפון גם כשהאפליקציה נסגרת, ונשלח רק עם הדוח. כשמסמנים בעיה נשמרות גם השורות האחרונות של הכתוביות.", "Kept on the phone even when the app closes, and sent only with the report. Marking a problem also keeps the last few caption lines."))
            }

            if !problemClips.isEmpty {
                Section {
                    ForEach(problemClips, id: \.self) { clip in
                        ShareLink(item: clip) {
                            Label(Self.clipTitle(clip), systemImage: "waveform")
                        }
                    }
                    .onDelete { offsets in
                        for index in offsets { viewModel.problemAudio?.remove(problemClips[index]) }
                        problemClips = viewModel.problemAudio?.clips() ?? []
                    }
                } header: {
                    Text(tr("הקלטות של בעיות שסומנו", "Sound from marked problems"))
                } footer: {
                    Text(tr("כשמסמנים בעיה, 30 השניות האחרונות של הקול נשמרות בטלפון בלבד, כדי להשוות בין מה שנאמר למה שהוצג. נשמרות עד 5 הקלטות, והן נשלחות רק כשמקישים על אחת מהן. כדי למחוק, מחליקים.", "Marking a problem keeps the last 30 seconds of sound, on the phone only, to compare what was said with what was shown. Up to 5 are kept, and they’re sent only when you tap one. Swipe to delete."))
                }
            }

            Section(tr("מודל ומילים", "Model and words")) {
                DiagnosticsRow(tr("מצב המודל", "Model state"), Self.describe(modelState))
                DiagnosticsRow(tr("טוקנייזר שמור", "Tokenizer cached"), store.hasCachedTokenizer() ? tr("כן", "Yes") : tr("לא (צריך אינטרנט פעם אחת)", "No (needs internet once)"))
                DiagnosticsRow(tr("שמות ומילים", "Names and words"), "\(viewModel.vocabulary.count)")
                DiagnosticsRow(tr("התראות על מילים", "Word alerts"), "\(viewModel.settings.keywordAlerts.filter(\.isEnabled).count)")
            }

            Section(tr("מכשיר", "Device")) {
                DiagnosticsRow(tr("טמפרטורה", "Temperature"), Self.describe(ProcessInfo.processInfo.thermalState))
                DiagnosticsRow(tr("מצב חיסכון בסוללה", "Low Power Mode"), ProcessInfo.processInfo.isLowPowerModeEnabled ? tr("פעיל", "On") : tr("כבוי", "Off"))
                DiagnosticsRow(tr("סוללה", "Battery"), DiagnosticsReport.batteryText)
                DiagnosticsRow(tr("מקום פנוי", "Free space"), DiagnosticsReport.freeSpaceText)
                DiagnosticsRow(tr("זיכרון", "Memory"), Self.memoryText)
                DiagnosticsRow(tr("דגם המכשיר", "Device model"), UIDevice.current.model)
                DiagnosticsRow("iOS", UIDevice.current.systemVersion)
                DiagnosticsRow(tr("אפליקציה", "App"), SettingsView.versionString)
                DiagnosticsRow(tr("ההתקנה תקפה עד", "Install valid until"), Self.installExpiryText)
            }
        }
        .task {
            reloadJournal()
            problemClips = viewModel.problemAudio?.clips() ?? []
            notificationsAllowed = await AlertNotifier.shared.isAllowed()
            builtInRecognition = await AppleSpeechEngine.onDeviceReport(languageCodes: ["he", viewModel.settings.languageCode])
            reportText = report
            // "Send report" shares this text as it stands: kept current
            // while the screen is open, not frozen at the moment it opened.
            // The journal and the notification answer too, less often:
            // the journal is a file read.
            var ticks = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                ticks += 1
                if ticks % 5 == 0 {
                    reloadJournal()
                    notificationsAllowed = await AlertNotifier.shared.isAllowed()
                }
                reportText = report
            }
        }
        .accessibilityIdentifier("diagnosticsScreen")
        .navigationTitle(tr("אבחון", "Diagnostics"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private let store = WhisperModelStore()

    private var modelState: ModelFolderState {
        store.state(of: viewModel.settings.whisperModelVariant)
    }

    private var notificationsText: String {
        switch notificationsAllowed {
        case false?: return tr("כבויות בהגדרות של iOS", "Turned off in iOS Settings")
        case nil: return tr("עוד לא נשאל", "Not asked yet")
        case true?: return AlertNotifier.shared.lastFailure == nil ? tr("תקינות", "Working") : tr("השליחה האחרונה נכשלה", "Last one failed to send")
        }
    }

    private var retryText: String {
        guard let retry = viewModel.pipeline.scheduledRetry else { return "—" }
        let seconds = max(0, Int((retry.at - Date().timeIntervalSince1970).rounded()))
        return tr("ניסיון %1, בעוד %2 שנ׳", "Attempt %1, in %2 s", args: ["\(retry.attempt)", "\(seconds)"])
    }

    private static var installExpiryText: String {
        guard let date = InstallExpiryStatus.shared.expiresAt else { return "—" }
        return date.formatted(inAppLanguage: .abbreviated, time: .shortened)
    }

    private static var vibrationText: String {
        let player = AlertHapticPlayer.shared
        guard player.supportsHaptics else { return tr("לא נתמך במכשיר הזה", "Not supported on this device") }
        return player.lastFailure == nil ? tr("תקין", "Working") : tr("נכשל, ולכן משתמשים ברטט רגיל", "Failed, using standard vibration instead")
    }

    /// "812 MB in use · 1.9 GB more": what the app uses, and how much more
    /// iOS lets it have before ending it.
    private static var memoryText: String {
        var parts: [String] = []
        if let used = DeviceMemory.footprintBytes() { parts.append(tr("%1 בשימוש", "%1 in use", args: ["\(DiagnosticsReport.format(bytes: used))"])) }
        if let left = DeviceMemory.availableBytes() { parts.append(tr("עוד %1", "%1 more", args: ["\(DiagnosticsReport.format(bytes: left))"])) }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    static func describe(_ state: ModelFolderState) -> String {
        switch state {
        case .missing: return tr("לא הורד", "Not downloaded")
        case .partial: return tr("ההורדה נקטעה", "Download interrupted")
        case .unverified: return tr("מותקן (לא אומת)", "Installed (unverified)")
        case .verified: return tr("מותקן", "Installed")
        }
    }

    static func describe(_ thermal: ProcessInfo.ThermalState) -> String {
        switch thermal {
        case .nominal: return tr("רגיל", "Normal")
        case .fair: return tr("מתחמם", "Warm")
        case .serious: return tr("חם · הכתוביות מאטות", "Hot · captions are slowing down")
        case .critical: return tr("חם מאוד · הכתוביות מאטות מאוד", "Very hot · captions are slowing a lot")
        @unknown default: return tr("לא ידוע", "Unknown")
        }
    }

    private var report: String {
        DiagnosticsReport(viewModel: viewModel, journalLines: journalLines, notificationsAllowed: notificationsAllowed, modelState: modelState, tokenizerCached: store.hasCachedTokenizer(), builtInRecognition: builtInRecognition).text
    }

    /// Alert sounds the classifier heard, but not surely enough to alert:
    /// tells "never heard the doorbell" from "heard it faintly".
    @ViewBuilder
    private var nearMissesSection: some View {
        let misses = viewModel.pipeline.soundNearMisses.recentFirst
        if !misses.isEmpty {
            Section {
                ForEach(misses, id: \.identifier) { miss in
                    DiagnosticsRow(
                        SoundEventCatalog.event(for: miss.identifier)?.name ?? miss.identifier,
                        "\(Int((miss.bestConfidence * 100).rounded()))% · \(Date(timeIntervalSince1970: miss.lastHeardAt).formatted(inAppLanguage: .omitted, time: .shortened))"
                    )
                }
            } header: {
                Text(tr("צלילים שנשמעו חלשים מדי בשביל התראה", "Sounds heard too faint to alert"))
            } footer: {
                Text(tr("התראה מופעלת רק כשהזיהוי בטוח לפחות ב־%1%. צליל שמופיע כאן נשמע, אבל היה רחוק או חלש מדי.", "An alert needs %1% confidence. A sound listed here was heard, but too far or too faint.", args: ["\(Int((viewModel.pipeline.soundAlertConfidence * 100).rounded()))"]))
            }
        }
    }

    /// The quiet, middle and loud ends of what the microphone heard, in
    /// Hebrew reading order: quiet first.
    static func levelsText(_ levels: AudioLevelHistogram) -> String {
        guard let quiet = levels.decibels(atFraction: 0.1),
              let middle = levels.decibels(atFraction: 0.5),
              let loud = levels.decibels(atFraction: 0.9)
        else { return "—" }
        return tr("שקט %1 · בינוני %2 · חזק %3", "quiet %1 · mid %2 · loud %3", args: ["\(quiet)", "\(middle)", "\(loud)"])
    }
}

/// A label with its value. At the largest text sizes the two side by side
/// left the value a narrow column that broke words mid-way, so there the
/// value goes under the label, as wide as the row.
private struct DiagnosticsRow: View {
    let label: String
    let value: String
    var identifier: String?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(_ label: String, identifier: String? = nil, _ value: String) {
        self.label = label
        self.value = value
        self.identifier = identifier
    }

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 4) {
                Text(label)
                    .accessibilityIdentifier(identifier.map { $0 + "Label" } ?? "")
                Text(value)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier(identifier.map { $0 + "Value" } ?? "")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            LabeledContent {
                Text(value)
                    .textSelection(.enabled)
                    .accessibilityIdentifier(identifier.map { $0 + "Value" } ?? "")
            } label: {
                Text(label)
                    .accessibilityIdentifier(identifier.map { $0 + "Label" } ?? "")
            }
        }
    }
}
