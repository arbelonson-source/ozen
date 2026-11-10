import SwiftUI
import OzenKit

private struct ModelSwitchQuestion: ViewModifier {
    @Binding var pending: WhisperModelOption?
    let viewModel: LiveCaptionViewModel

    func body(content: Content) -> some View {
        content.confirmationDialog(
            tr("להוריד את המודל ״\u{2068}%1\u{2069}״ ולעבור אליו?", "Download %1 and switch to it?", args: ["\(pending?.displayName ?? "")"]),
            isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
            titleVisibility: .visible,
            presenting: pending
        ) { option in
            Button(tr("להוריד ולעבור", "Download and switch")) {
                Task { await viewModel.chooseModel(option.variant) }
            }
            Button(tr("ביטול", "Cancel"), role: .cancel) {}
        } message: { option in
            Text(tr("הכתוביות ייעצרו עד שההורדה (%1) תסתיים והמודל ייטען. בלי Wi‑Fi, ייתכן שההורדה תחכה עד שיהיה חיבור.", "Captions will stop until the download (%1) finishes and the model loads. Without Wi‑Fi, the download may wait for it.", args: ["\(option.sizeLabel)"]))
        }
    }
}

extension View {
    func asksBeforeSwitchingModel(_ pending: Binding<WhisperModelOption?>, viewModel: LiveCaptionViewModel) -> some View {
        modifier(ModelSwitchQuestion(pending: pending, viewModel: viewModel))
    }
}
