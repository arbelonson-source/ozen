import SwiftUI
import UIKit
import AVFAudio
import OzenKit

struct SpeakerEnrollmentView: View {
    let viewModel: LiveCaptionViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var name = ""
    @State private var isRecording = false
    @State private var progress: Double = 0
    @State private var failed = false
    @State private var noMicrophone = false
    @State private var recording: Task<Void, Never>?

    private let targetSeconds = 30.0

    var body: some View {
        NavigationStack {
            Form {
                Section(tr("שם", "Name")) {
                    TextField(tr("לדוגמה: סבתא", "For example: Grandma"), text: $name)
                        .textInputAutocapitalization(.words)
                        .disabled(isRecording)
                }

                Section {
                    Text(tr("בקשו מהאדם לדבר בטבעיות, במרחק רגיל מהמיקרופון שנבחר. אם הפס לא זז כשמדברים, המיקרופון לא קולט כלום. הכתוביות מושהות בזמן ההקלטה.", "Ask the person to speak naturally, at a normal distance from the selected microphone. If the bar doesn’t move while the person speaks, the microphone isn’t hearing anything. Captions are paused during recording."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if isRecording {
                        ProgressView(value: progress) {
                            Text(tr("מקליטים… %1/%2 שניות", "Recording… %1/%2 seconds", args: ["\(Int(progress * targetSeconds))", "\(Int(targetSeconds))"]))
                        }
                        .accessibilityValue(ConversationStats.secondsText(Int(progress * targetSeconds)))
                        LevelMeter(level: viewModel.inputLevel, isActive: true)
                        Button(tr("עצירה בלי לשמור", "Stop without saving"), role: .destructive) {
                            recording?.cancel()
                        }
                    } else {
                        Button {
                            record()
                        } label: {
                            Label(tr("הקלטה ושמירה (%1 שניות)", "Record and save (%1 seconds)", args: ["\(Int(targetSeconds))"]), systemImage: "record.circle")
                        }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                } header: {
                    Text(tr("הקלטה", "Recording"))
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(tr("כדי לשפר את הזיהוי, הקליטו כל אדם 3–4 פעמים תחת אותו שם, בימים שונים ובמקומות שונים: בחדר שקט, כשהטלוויזיה פועלת ברקע ומהצד השני של החדר.", "For better recognition, record each person 3–4 times under the same name, on different days and in different places: a quiet room, with the TV on, from across the room."))
                        Text(tr("ההקלטה עצמה לא נשמרת — רק ״טביעת קול״ מספרית קצרה שממנה אי אפשר לשחזר את הדיבור.", "The recording itself isn’t saved — only a short numeric “voiceprint” that the speech can’t be reconstructed from."))
                    }
                }
            }
            .accessibilityIdentifier("speakerEnrollmentScreen")
            .navigationTitle(tr("דובר חדש", "New speaker"))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isRecording)
            .announcing(tr("מקליטים, אפשר לדבר עכשיו", "Recording, speak now"), whenTurningTrue: isRecording)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("ביטול", "Cancel")) {
                        recording?.cancel()
                        dismiss()
                    }
                }
            }
            .alert(tr("ההקלטה קצרה או שקטה מדי", "The recording is too short or too quiet"), isPresented: $failed) {
                Button(tr("אישור", "OK"), role: .cancel) {}
            } message: {
                Text(tr("לא הצלחנו להפיק טביעת קול. ודאו שהמיקרופון הנכון נבחר ושהאדם מדבר לאורך כל ההקלטה.", "A voiceprint couldn’t be made. Make sure the right microphone is selected and the person speaks throughout the recording."))
            }
        }
        .alert(tr("אין גישה למיקרופון", "No microphone access"), isPresented: $noMicrophone) {
            Button(tr("פתיחת הגדרות הטלפון", "Open the phone’s settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            Button(tr("ביטול", "Cancel"), role: .cancel) {}
        } message: {
            Text(tr("כדי להקליט קול צריך גישה למיקרופון. אפשר לאשר אותה בהגדרות הטלפון.", "Recording a voice needs microphone access. You can allow it in the phone’s Settings."))
        }
    }

    private func record() {
        Task {
            if await AVAudioApplication.requestRecordPermission() {
                startRecording()
            } else {
                noMicrophone = true
            }
        }
    }

    private func startRecording() {
        isRecording = true
        progress = 0
        let speaker = name.trimmingCharacters(in: .whitespaces)
        recording = Task {
            let saved = await viewModel.enroll(
                name: speaker,
                seconds: targetSeconds
            ) { fraction in
                progress = fraction
            }
            isRecording = false
            if saved {
                Announcement.post(tr("הקול נשמר: %1", "Voice saved: %1", args: ["\(speaker)"]))
                dismiss()
            } else if !Task.isCancelled {
                failed = true
            }
        }
    }
}
