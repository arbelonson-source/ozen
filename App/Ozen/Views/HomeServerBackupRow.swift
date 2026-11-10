import SwiftUI
import OzenKit

struct HomeServerBackupRow: View {
    @Bindable var viewModel: LiveCaptionViewModel
    @State private var confirmingCellular = false

    var body: some View {
        let status = viewModel.backupModelStatus
        VStack(alignment: .leading, spacing: 8) {
            switch status {
            case .notNeeded:
                EmptyView()
            case .ready:
                Label(Self.message(status) ?? "", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.readable(.green))
            case .missing(let megabytes):
                downloadButton(megabytes: megabytes)
                note(tr("בלי הגיבוי, הכתוביות נעצרות כשאין חיבור למחשב.", "Without the backup, captions stop when the computer can’t be reached."))
            case .downloading(let fraction):
                ProgressView(value: fraction) {
                    Text(tr("מורידים את הגיבוי · %1%", "Downloading the backup · %1%", args: ["\(Int((fraction * 100).rounded()))"]))
                }
                .accessibilityLabel(Text(Self.message(status) ?? ""))
            case .waitingForWiFi:
                note(Self.message(status) ?? "")
                Button {
                    confirmingCellular = true
                } label: {
                    Label(tr("להוריד עכשיו בחבילת הגלישה", "Download now over cellular data"), systemImage: "antenna.radiowaves.left.and.right")
                }
            case .offline:
                note(Self.message(status) ?? "")
            case .notEnoughRoom:
                Label(Self.message(status) ?? "", systemImage: "externaldrive.badge.exclamationmark")
                    .foregroundStyle(.readable(.red))
            case .failed:
                downloadButton(megabytes: nil)
                Label(Self.message(status) ?? "", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.readable(.orange))
            }
        }
        .accessibilityIdentifier("homeServerBackupRow")
        .confirmationDialog(tr("להוריד את המודל בחבילת הגלישה?", "Download the model over cellular data?"), isPresented: $confirmingCellular, titleVisibility: .visible) {
            Button(tr("להוריד עכשיו בחבילת הגלישה", "Download now over cellular data")) {
                viewModel.downloadBackupModel(overCellular: true)
            }
            Button(tr("ביטול", "Cancel"), role: .cancel) {}
        } message: {
            Text(tr("גודל המודל הוא בערך %1. הורדה בחבילת הגלישה עלולה לעלות כסף או לגמור את נפח הגלישה.", "The model is about %1. Over cellular data this can cost money or use up your data plan.", args: ["\(PhasePresentation.sizeText(megabytes: WhisperModelCatalog.option(for: viewModel.settings.whisperModelVariant)?.sizeMB ?? 0))"]))
        }
        .onChange(of: status) { previous, now in
            guard BackupModel.announces(now, after: previous), let text = Self.message(now) else { return }
            Announcement.post(text)
        }
    }

    private static func message(_ status: BackupModelStatus) -> String? {
        switch status {
        case .notNeeded, .missing:
            return nil
        case .downloading:
            return tr("מורידים את הגיבוי", "Downloading the backup")
        case .ready:
            return tr("הגיבוי בטלפון מוכן: כשאין חיבור למחשב, הטלפון ממשיך לבד.", "Backup on the phone is ready: when the computer can’t be reached, the phone carries on by itself.")
        case .waitingForWiFi:
            return tr("הגיבוי יורד רק ב־Wi‑Fi. התחברו ל־Wi‑Fi כדי להוריד אותו.", "The backup downloads only on Wi‑Fi. Connect to Wi‑Fi to download it.")
        case .offline:
            return tr("אין חיבור לאינטרנט, ולכן אי אפשר להוריד את הגיבוי עכשיו.", "There’s no internet, so the backup can’t be downloaded now.")
        case .notEnoughRoom(let megabytes):
            return tr("אין מספיק מקום בטלפון לגיבוי. צריך לפנות עוד %1.", "Not enough room on the phone for the backup. %1 more needs to be freed up.", args: ["\(PhasePresentation.sizeText(megabytes: megabytes))"])
        case .failed:
            return tr("הורדת הגיבוי נעצרה. נסו שוב.", "The backup download stopped. Try again.")
        }
    }

    private func downloadButton(megabytes: Int?) -> some View {
        Button {
            viewModel.downloadBackupModel()
        } label: {
            if let megabytes {
                Label(tr("הורדת גיבוי לטלפון (%1)", "Download a backup to the phone (%1)", args: ["\(PhasePresentation.sizeText(megabytes: megabytes))"]), systemImage: "arrow.down.circle")
            } else {
                Label(tr("הורדת גיבוי לטלפון", "Download a backup to the phone"), systemImage: "arrow.down.circle")
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
    }
}
