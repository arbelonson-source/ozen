import SwiftUI
import OzenKit

/// Turns one recording or video into text, on this phone. From History it
/// is pushed onto the stack; handed a file from outside, it sits in a sheet
/// of its own and `onClose` is how it leaves.
struct TranscribeRecordingView: View {
    @Bindable var model: TranscribeRecordingModel
    var onClose: (() -> Void)?
    @State private var confirmingStop = false

    var body: some View {
        content
            .navigationTitle(tr("המרת הקלטה לטקסט", "Transcribe a recording"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(model.isWorking)
            .interactiveDismissDisabled(model.isWorking)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if model.isWorking {
                        Button(tr("עצירה", "Stop")) { confirmingStop = true }
                            .accessibilityIdentifier("transcribeStopButton")
                    } else if let onClose {
                        Button(tr("סגירה", "Close"), action: onClose)
                    }
                }
            }
            .task { await model.load() }
            .onDisappear { Task { await model.close() } }
            .confirmationDialog(
                tr("לעצור את ההמרה לטקסט?", "Stop turning the recording into text?"),
                isPresented: $confirmingStop,
                titleVisibility: .visible
            ) {
                Button(tr("עצירה", "Stop"), role: .destructive) { model.stop() }
                Button(tr("להמשיך", "Keep going"), role: .cancel) {}
            } message: {
                Text(tr("מה שכבר הומר לא יישמר.", "What was already turned into text won’t be saved."))
            }
            .accessibilityIdentifier("transcribeRecordingScreen")
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .idle:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready(let ready):
            readyForm(ready)
        case .working(let working):
            workingList(working)
        case .done(let done):
            doneList(done)
        case .failed(let name, let problem):
            failedList(name: name, problem: problem)
        }
    }

    private func readyForm(_ ready: FileTranscription.Ready) -> some View {
        Form {
            Section {
                Text(ready.name)
                    .font(.headline)
                if let seconds = ready.durationSeconds {
                    Text(tr("אורך: %1", "Length: %1", args: ["\(TranscriptFormats.clock(seconds))"]))
                }
                Text(tr("ההקלטה נשארת בטלפון. שום דבר לא נשלח לשום מקום.", "The recording stays on this phone. Nothing is sent anywhere."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section {
                Picker(tr("שפת ההקלטה", "Language of the recording"), selection: Binding(get: { ready.language }, set: { model.setLanguage($0) })) {
                    ForEach(UILanguage.allCases, id: \.self) { language in
                        Text(language.spokenName).tag(language)
                    }
                }
                modelRow(ready)
            }
            if ready.captionsRunning {
                Section {
                    Text(tr("מודל אחד לא יכול להפעיל גם כתוביות חיות וגם המרת הקלטה. לעצור את הכתוביות?", "One model can’t run live captions and a recording at the same time. Stop the captions?"))
                    Button(tr("עצירת הכתוביות", "Stop captions")) {
                        Task { await model.stopCaptions() }
                    }
                } header: {
                    Text(tr("הכתוביות פועלות עכשיו", "Captions are on right now"))
                }
            }
            Section {
                Button {
                    model.start()
                } label: {
                    Text(ready.model == nil ? tr("הורדה והמרה לטקסט", "Download and transcribe") : tr("המרה לטקסט", "Transcribe"))
                        .frame(maxWidth: .infinity)
                }
                .disabled(ready.captionsRunning)
                .accessibilityIdentifier("transcribeStartButton")
            } footer: {
                Text(tr("הקלטה ארוכה לוקחת זמן. כדאי להשאיר את אוזן פתוח ואת הטלפון מחובר לטעינה.", "A long recording takes a while. Keep Ozen open, and the phone charging."))
            }
        }
    }

    @ViewBuilder
    private func modelRow(_ ready: FileTranscription.Ready) -> some View {
        if ready.installed.count > 1 {
            Picker(tr("מודל להמרה", "Model to use"), selection: Binding(get: { ready.model?.variant ?? "" }, set: { model.setModel($0) })) {
                ForEach(ready.installed) { option in
                    Text(option.displayName).tag(option.variant)
                }
            }
        } else if let chosen = ready.model {
            Text(tr("מודל: %1", "Model: %1", args: ["\(chosen.displayName)"]))
        } else {
            Text(tr(
                "אין בטלפון מודל לשפה %1. אוזן יוריד את \"%2\" (%3 MB) לפני ההמרה לטקסט.",
                "There is no model for %1 on this phone. Ozen will download “%2” (%3 MB) before turning the recording into text.",
                args: ["\(ready.language.spokenName)", "\(ready.toDownload.displayName)", "\(ready.toDownload.sizeMB)"]
            ))
        }
    }

    private func workingList(_ working: FileTranscription.Working) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(Self.stageTitle(working.stage))
                        .font(.headline)
                    if let fraction = working.fraction {
                        ProgressView(value: fraction)
                        Text("\(TranscriptionProgress.percent(fraction))%")
                            .monospacedDigit()
                    } else {
                        ProgressView()
                    }
                    if let left = working.timeLeftSeconds {
                        Text(TranscriptionProgress.timeLeftText(left))
                    }
                    Text(tr("מודל: %1", "Model: %1", args: ["\(working.model.displayName)"]))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("transcribeProgress")
            }
            if !working.lines.isEmpty {
                Section {
                    linesRows(working.lines, language: working.language)
                }
            }
        }
    }

    private func doneList(_ done: FileTranscription.Done) -> some View {
        List {
            Section {
                Label(tr("ההקלטה הומרה לטקסט", "Recording turned into text"), systemImage: "checkmark.circle.fill")
                    .font(.headline)
                Text(done.name)
                if done.history == .failed {
                    Text(done.history.message)
                        .foregroundStyle(.readable(.orange))
                } else {
                    Text(done.history.message)
                        .foregroundStyle(.secondary)
                }
                if done.endMissing {
                    Text(tr("סוף ההקלטה לא נקרא, ולכן הטקסט נעצר מוקדם.", "The end of this recording couldn’t be read, so the text stops early."))
                        .foregroundStyle(.readable(.orange))
                }
            }
            Section {
                ShareLink(item: model.shareText) {
                    Label(tr("שיתוף כטקסט", "Share as text"), systemImage: "square.and.arrow.up")
                }
                if let subtitles = model.subtitlesURL {
                    ShareLink(item: subtitles) {
                        Label(tr("כתוביות (SRT)", "Subtitles (SRT)"), systemImage: "captions.bubble")
                    }
                }
            }
            Section {
                linesRows(done.lines, language: done.language)
            }
        }
        .accessibilityIdentifier("transcribeDone")
    }

    private func failedList(name: String, problem: FileTranscriptionProblem) -> some View {
        List {
            Section {
                Text(name)
                    .font(.headline)
                Label(problem.message, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.readable(.orange))
            }
            if problem != .couldNotRead, problem != .noAudio {
                Section {
                    Button(tr("נסו שוב", "Try again")) {
                        Task { await model.load() }
                    }
                }
            }
        }
    }

    private func linesRows(_ lines: [TranscribedLine], language: UILanguage) -> some View {
        let code = language.languageCode.identifier
        return ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
            VStack(alignment: .leading, spacing: 2) {
                Text(TranscriptFormats.clock(line.startSeconds))
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                Text(CaptionLayout.directed(line.text, languageCode: code))
            }
            .accessibilityElement(children: .combine)
        }
    }

    private static func stageTitle(_ stage: FileTranscription.Working.Stage) -> String {
        switch stage {
        case .downloading: return tr("מורידים את המודל", "Downloading the model")
        case .loading: return tr("מכינים את המודל", "Getting the model ready")
        case .transcribing: return tr("ממירים את ההקלטה לטקסט", "Turning the recording into text")
        }
    }
}
