import SwiftUI
import OzenKit

struct WordFixesSection: View {
    @Bindable var viewModel: LiveCaptionViewModel
    @State private var heard = ""
    @State private var written = ""

    private var canAdd: Bool {
        !WordFixes.normalized([WordFix(heard: heard, written: written)]).isEmpty
            && viewModel.wordFixes.count < WordFixes.maximumFixes
    }

    var body: some View {
        Section {
            ForEach(viewModel.wordFixes, id: \.self) { fix in
                HStack {
                    Text(fix.heard).foregroundStyle(.secondary)
                    Image(systemName: "arrow.forward").accessibilityHidden(true)
                    Text(fix.written)
                }
                .font(.body)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(fix.heard) → \(fix.written)")
                .accessibilityAction(named: tr("מחיקת התיקון %1", "Delete the fix %1", args: ["\(fix.heard) → \(fix.written)"])) {
                    if let index = viewModel.wordFixes.firstIndex(of: fix) {
                        viewModel.removeWordFixes(at: IndexSet(integer: index))
                    }
                }
            }
            .onDelete { viewModel.removeWordFixes(at: $0) }
            VStack(alignment: .leading) {
                TextField(tr("מה נשמע", "Heard"), text: $heard)
                    .autocorrectionDisabled()
                TextField(tr("מה לכתוב", "Written"), text: $written)
                    .autocorrectionDisabled()
                Button(tr("הוספת תיקון", "Add fix")) {
                    viewModel.addWordFix(WordFix(heard: heard, written: written))
                    heard = ""
                    written = ""
                }
                .disabled(!canAdd)
            }
        } header: {
            Text(tr("תיקוני מילים", "Word fixes"))
        } footer: {
            Text(tr("מילה ששמעו לא נכון תיכתב תמיד כמו שתכתבו כאן.", "A word heard wrongly is always written the way you set here."))
        }
    }
}
