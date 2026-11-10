import SwiftUI
import OzenKit
import OzenPlatform

/// "Problem marked": where the details went, and one large button that
/// sends the report to whoever helps her through the share sheet, so the
/// help doesn't wait for a trip to Settings → Diagnostics. The report is
/// built as the sheet opens; until it is ready the button waits, so a tap
/// never shares an empty text.
struct ProblemMarkedSheet: View {
    let viewModel: LiveCaptionViewModel
    @State private var report: String?
    @Environment(\.dismiss) private var dismiss

    private var message: String {
        viewModel.problemKeptSound
            ? tr("פרטי התקלה, השורות האחרונות ו־30 השניות האחרונות של הקול נשמרו בטלפון בלבד. הכפתור שולח את הפרטים והשורות; את הקול שולחים מהגדרות ← אבחון.", "The problem’s details, the last few lines and the last 30 seconds of sound were saved, on the phone only. The button sends the details and lines; the sound is sent from Settings → Diagnostics.")
            : tr("פרטי התקלה והשורות האחרונות נשמרו בטלפון בלבד.", "The problem’s details and the last few lines were saved, on the phone only.")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(message)
                        .fixedSize(horizontal: false, vertical: true)
                    sendButton
                        .ozenGlassButton(prominent: true)
                        .controlSize(.large)
                        .accessibilityHint(tr("פותח את אפשרויות השיתוף עם דוח האבחון", "Opens the share options with the diagnostics report"))
                        .accessibilityIdentifier("problemMarkedSend")
                    Button {
                        dismiss()
                    } label: {
                        Text(tr("אישור", "OK"))
                            .frame(maxWidth: .infinity)
                    }
                    .ozenGlassButton()
                    .controlSize(.large)
                }
                .font(.title3)
                .padding(24)
            }
            .navigationTitle(tr("הבעיה סומנה", "Problem marked"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .task(id: viewModel.problemMarkedAt) {
            report = nil
            // The journal waits for its file: read away from the main
            // thread, so the sheet and its waiting button show at once.
            let kept = viewModel.journal
            let journal = await Task.detached(priority: .userInitiated) {
                kept?.reportLines(utcOffsetAt: { DiagnosticsReport.utcOffset(at: $0) }) ?? []
            }.value
            let notificationsAllowed = await AlertNotifier.shared.isAllowed()
            let builtInRecognition = await AppleSpeechEngine.onDeviceReport(languageCodes: ["he", viewModel.settings.languageCode])
            let store = WhisperModelStore()
            report = DiagnosticsReport(
                viewModel: viewModel,
                journalLines: journal,
                notificationsAllowed: notificationsAllowed,
                modelState: store.state(of: viewModel.settings.whisperModelVariant),
                tokenizerCached: store.hasCachedTokenizer(),
                builtInRecognition: builtInRecognition
            ).forHelper(markedAt: viewModel.problemMarkedAt ?? Date().timeIntervalSince1970)
        }
    }

    @ViewBuilder
    private var sendButton: some View {
        if let report {
            ShareLink(item: report, subject: Text(tr("דוח אבחון של אוזן", "Ozen diagnostics report"))) {
                sendLabel
            }
        } else {
            Button {} label: {
                HStack(spacing: 12) {
                    ProgressView()
                    sendLabel
                }
            }
            .disabled(true)
        }
    }

    private var sendLabel: some View {
        Label(tr("שליחה למי שעוזר לכם", "Send to whoever helps you"), systemImage: "square.and.arrow.up")
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
    }
}
