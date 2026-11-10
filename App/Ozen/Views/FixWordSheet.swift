import SwiftUI
import OzenKit

struct FixWordSheet: View {
    let text: String
    let languageCode: String
    /// What Save says when the line didn't take the fix.
    var notSaved = tr("השורה כבר לא על המסך, ולכן התיקון לא נשמר.", "The line is no longer on the screen, so the fix was not saved.")
    /// Returns whether the line changed.
    let onSave: (WordFix, Bool) -> Bool
    @State private var picked: ClosedRange<Int>?
    @State private var heard = ""
    @State private var written = ""
    @State private var always = false
    /// Save closed the sheet whether or not the line changed, so a fix that
    /// never landed looked done.
    @State private var rejected = false
    /// After a word is picked the keyboard opens on how it should be
    /// written: at large text the fields sat below the edge, and a grey
    /// Save was all she had to go on.
    @FocusState private var writing: Bool
    @Environment(\.dismiss) private var dismiss

    private var words: [String] {
        CaptionLayout.displayText(text).split(whereSeparator: \.isWhitespace).map(String.init)
    }
    private var fix: WordFix { WordFix(heard: heard, written: written) }
    /// Saving needs a fix that changes this line: a typed word the line
    /// doesn't have changed nothing, yet Save closed the sheet as if it had
    /// worked and the word still went into the list. As on Android.
    private var canSave: Bool { FixWordForm.changes(line: text, heard: heard, written: written) }
    private var note: FixWordForm.Note? { FixWordForm.note(line: text, heard: heard, written: written) }

    var body: some View {
        NavigationStack {
            Form {
                if rejected {
                    Section {
                        Text(notSaved)
                            .foregroundStyle(ReadableColor(color: .red))
                            .accessibilityIdentifier("fixWordNotSaved")
                    }
                }
                Section {
                    FixWordFlow {
                        ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                            wordButton(index, word)
                        }
                    }
                    .environment(\.layoutDirection, CaptionLayout.readsRightToLeft(text, languageCode: languageCode) ? .rightToLeft : .leftToRight)
                } header: {
                    Text(tr("הקישו על המילים שנכתבו לא נכון", "Tap the words that came out wrong"))
                }
                Section {
                    // Names and words the dictionary doesn't know are what
                    // this sheet is for: autocorrect turned them into other
                    // words, and a misheard word it "repaired" no longer
                    // matched the line. As in Settings' word fixes.
                    TextField(tr("או הקלידו את המילה השגויה", "Or type the wrong word"), text: $heard)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField(tr("איך זה צריך להיכתב", "How it should be written"), text: $written)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .focused($writing)
                    if let note {
                        Text(noteText(note))
                            .font(.footnote)
                            .foregroundStyle(ReadableColor(color: note == .notInLine ? .red : .secondary))
                    }
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
                        if onSave(fix, always) {
                            Announcement.post(tr("השורה תוקנה", "Line fixed"))
                            dismiss()
                        } else {
                            rejected = true
                            Announcement.post(notSaved)
                        }
                    }
                    .disabled(!canSave)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("ביטול", "Cancel")) { dismiss() }
                }
            }
        }
        // Typed text that no longer matches the picked words takes over,
        // so no word still reads as selected while another is fixed.
        .onChange(of: heard) { _, typed in
            if let picked, typed != pickedText(picked) { self.picked = nil }
        }
        // VoiceOver heard neither why Save stayed grey nor that a typed
        // word isn't in the line; Android's note is a live region.
        .onChange(of: note) { _, note in
            if let note { Announcement.post(noteText(note)) }
        }
        // Half the screen left Fix it every time below the edge of an
        // iPhone SE; seven tenths still shows the caption above the sheet.
        .presentationDetents([.fraction(0.7), .large])
    }

    private func noteText(_ note: FixWordForm.Note) -> String {
        note == .notInLine
            ? tr("המילה הזו לא מופיעה בשורה", "This word is not in the line")
            : tr("עכשיו כתבו איך זה צריך להיכתב", "Now type how it should be written")
    }

    private func pickedText(_ range: ClosedRange<Int>) -> String {
        let edge = CharacterSet.punctuationCharacters.union(.whitespaces)
        return FixWordForm.cleaned(words[range].joined(separator: " ")).trimmingCharacters(in: edge)
    }

    private func wordButton(_ index: Int, _ word: String) -> some View {
        let selected = picked?.contains(index) == true
        return Button { tap(index) } label: {
            Text(word)
                .font(selected ? .body.weight(.bold) : .body)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                // 44 points: the smallest target a shaky finger hits
                // reliably; the padding alone left about 34.
                .frame(minWidth: 44, minHeight: 44)
                .background(selected ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                // The tint alone was hard to see with low vision or in a
                // bright room.
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: 8).strokeBorder(Color.accentColor, lineWidth: 3)
                    }
                }
                .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(word)
        .accessibilityHint(tr("סימון המילה לתיקון", "Marks the word to fix"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// As on Android: a picked word tapped again is let go (the end of a
    /// run, or the only word), so a neighbour joined by a near-miss can be
    /// taken off again; before, a pick could only grow or move.
    private func tap(_ index: Int) {
        picked = FixWordForm.tapped(picked, index: index, words: words)
        if let picked {
            heard = pickedText(picked)
            if written.isEmpty { writing = true }
        } else {
            heard = ""
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
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            subview.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: ProposedViewSize(width: min(size.width, bounds.width), height: size.height))
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (size: CGSize, points: [CGPoint]) {
        var points: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
            size.width = min(size.width, width)
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
