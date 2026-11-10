import SwiftUI
import OzenKit

struct WordFixesSection: View {
    @Bindable var viewModel: LiveCaptionViewModel
    @State private var heard = ""
    @State private var written = ""

    private var canAdd: Bool {
        WordFixes.canAdd(WordFix(heard: heard, written: written), to: viewModel.wordFixes)
    }

    private var refusalNote: String? {
        WordFixes.refusalNote(WordFixes.refusal(WordFix(heard: heard, written: written), to: viewModel.wordFixes))
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
                .accessibilityLabel(WordFixes.spokenLabel(fix, in: Localization.language))
                .accessibilityAction(named: tr("מחיקת התיקון %1", "Delete the fix %1", args: ["\(WordFixes.spokenLabel(fix, in: Localization.language))"])) {
                    if let index = viewModel.wordFixes.firstIndex(of: fix) {
                        viewModel.removeWordFixes(at: IndexSet(integer: index))
                    }
                }
            }
            .onDelete { viewModel.removeWordFixes(at: $0) }
            VStack(alignment: .leading) {
                TextField(tr("מה שנשמע", "Heard"), text: $heard)
                    .autocorrectionDisabled()
                TextField(tr("מה שייכתב", "Written"), text: $written)
                    .autocorrectionDisabled()
                Button(tr("הוספת תיקון", "Add fix")) {
                    viewModel.addWordFix(WordFix(heard: heard, written: written))
                    heard = ""
                    written = ""
                    Announcement.post(tr("התיקון נוסף", "Fix added"))
                }
                .disabled(!canAdd)
                .accessibilityHint(refusalNote ?? "")
                if let refusalNote {
                    Text(refusalNote)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(tr("תיקוני מילים", "Word fixes"))
        } footer: {
            Text(tr("מילה שזוהתה לא נכון תיכתב תמיד כפי שתכתבו כאן.", "A word heard wrongly is always written the way you set here."))
        }
    }
}
