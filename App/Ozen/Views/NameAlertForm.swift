import SwiftUI
import OzenKit

/// Type her name (or tap "savta", grandma) and the phone buzzes when it's said. Used
/// by the walkthrough and by the caption screen's offer to phones set up
/// before the walkthrough asked.
struct NameAlertForm: View {
    let viewModel: LiveCaptionViewModel
    @State private var nameDraft = ""
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var formLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 10))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            formLayout {
                TextField(tr("השם שלכם", "Your name"), text: $nameDraft)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.done)
                    .onSubmit { addName() }
                    .announcing(otherLettersNote, whenTurningTrue: HebrewText.isInOtherLetters(nameDraft, captionLanguage: viewModel.settings.languageCode))
                Button(tr("הוספה", "Add")) { addName() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : nil)
                    .disabled(nameDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if HebrewText.isInOtherLetters(nameDraft, captionLanguage: viewModel.settings.languageCode) || viewModel.settings.keywordAlerts.contains(where: { $0.isEnabled && HebrewText.isInOtherLetters($0.phrase, captionLanguage: viewModel.settings.languageCode) }) {
                Text(otherLettersNote)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            formLayout {
                ForEach(AlertSuggestions.offeredNames(spoken: viewModel.settings.spokenLanguage), id: \.self) { word in
                    suggestionButton(word)
                }
            }
            if let addedText = AlertSuggestions.vibrationNote(for: viewModel.settings.keywordAlerts, in: viewModel.uiLanguage, captionLanguage: viewModel.settings.languageCode) {
                Label(addedText, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.readable(.green))
            }
        }
        // A name typed and then "Next" or "Done" instead of "Add" was
        // dropped, and the name alert never came.
        .onDisappear { addName(announcing: false) }
    }

    private var otherLettersNote: String {
        tr("הכתוביות נכתבות באותיות של שפת הדיבור, ולכן ייתכן שמילה שנכתבה באותיות אחרות לא תימצא אף פעם.", "Captions are written in the letters of the spoken language, so a word written in other letters may never be found.")
    }

    private func suggestionButton(_ word: String) -> some View {
        let added = viewModel.settings.keywordAlerts.contains {
            $0.isEnabled && HebrewText.normalize($0.phrase) == HebrewText.normalize(word)
        }
        return Button {
            viewModel.addKeywordAlert(phrase: word)
            Announcement.added(AlertSuggestions.label(for: word, in: viewModel.uiLanguage))
        } label: {
            Label(AlertSuggestions.label(for: word, in: viewModel.uiLanguage), systemImage: added ? "checkmark" : "plus")
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(added)
        .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : nil)
    }

    /// Said aloud only when the person added it here: on the way out of the
    /// page, the next page is what VoiceOver should be reading.
    private func addName(announcing: Bool = true) {
        let name = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let wasOn = viewModel.listedKeywordAlert(matching: name)?.isEnabled == true
        viewModel.addKeywordAlert(phrase: name)
        // A name the engine has never heard is spelled some other way, and
        // then never matches; on the names list, every engine expects it.
        viewModel.addVocabularyTerm(name)
        nameDraft = ""
        if announcing, !wasOn { Announcement.added(name) }
    }
}

/// The caption screen's offer, as a sheet: the form, and a way out.
struct NameAlertSheet: View {
    let viewModel: LiveCaptionViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(tr("כשמישהו אומר את השם שלכם, הטלפון רוטט והשורה מסומנת, גם כשלא מסתכלים על המסך.", "When someone says your name, the phone vibrates and the line is marked, even when you’re not looking at the screen."))
                    NameAlertForm(viewModel: viewModel)
                    Text(tr("אפשר להוסיף עוד מילים או למחוק מילים בהגדרות ← התראות ← מילים חשובות.", "You can add more words, or delete them, in Settings → Alerts → Important words."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .font(.title3)
                .padding(24)
            }
            .navigationTitle(tr("כשקוראים לכם", "When your name is called"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("סיום", "Done")) { dismiss() }
                }
            }
        }
    }
}
