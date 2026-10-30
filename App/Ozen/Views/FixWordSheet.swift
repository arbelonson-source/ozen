import SwiftUI
import OzenKit

struct FixWordSheet: View {
    let text: String
    let onSave: (WordFix, Bool) -> Void
    @State private var picked: ClosedRange<Int>?
    @State private var heard = ""
    @State private var written = ""
    @State private var always = false
    @Environment(\.dismiss) private var dismiss

    private var words: [String] {
        CaptionLayout.displayText(text).split(whereSeparator: \.isWhitespace).map(String.init)
    }
    private var fix: WordFix { WordFix(heard: heard, written: written) }
    private var canSave: Bool { !WordFixes.normalized([fix]).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FixWordFlow {
                        ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                            wordButton(index, word)
                        }
                    }
                } header: {
                    Text(tr("נגעו במילים שנשמעו לא נכון", "Tap the words that came out wrong"))
                }
                Section {
                    TextField(tr("או הקלידו מה נשמע", "Or type what was heard"), text: $heard)
                    TextField(tr("איך זה צריך להיכתב", "How it should be written"), text: $written)
                }
                Section {
                    Toggle(tr("תיקון בכל פעם", "Fix it every time"), isOn: $always)
                }
            }
            .navigationTitle(tr("תיקון מילה", "Fix a word"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("שמירה", "Save")) {
                        onSave(fix, always)
                        dismiss()
                    }
                    .disabled(!canSave)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("ביטול", "Cancel")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func wordButton(_ index: Int, _ word: String) -> some View {
        let selected = picked?.contains(index) == true
        return Button { tap(index) } label: {
            Text(word)
                .font(.body)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(word)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func tap(_ index: Int) {
        if let range = picked, range.count < 4 {
            if index == range.upperBound + 1 { picked = range.lowerBound...index }
            else if index == range.lowerBound - 1 { picked = index...range.upperBound }
            else { picked = index...index }
        } else {
            picked = index...index
        }
        if let range = picked {
            let edge = CharacterSet.punctuationCharacters.union(.whitespaces)
            heard = words[range].joined(separator: " ").trimmingCharacters(in: edge)
        }
    }
}

private struct FixWordFlow: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(proposal.width ?? 300, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let laid = arrange(bounds.width, subviews)
        for (subview, point) in zip(subviews, laid.points) {
            subview.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified)
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        var points: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + 6
                rowHeight = 0
            }
            points.append(CGPoint(x: x, y: y))
            x += size.width + 6
            widest = max(widest, x)
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: widest, height: y + rowHeight), points)
    }
}
