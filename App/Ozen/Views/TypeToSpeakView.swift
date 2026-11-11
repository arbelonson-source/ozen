import SwiftUI
import OzenKit

/// The other direction of the conversation: the reader types a reply (or
/// taps one she uses all the time) and the phone says it. Captions pause
/// while the phone talks so it doesn't caption itself.
struct TypeToSpeakView: View {
    @Bindable var viewModel: LiveCaptionViewModel
    /// Kept on the view model, not here: a swipe down, "Close", or a
    /// Shortcut opening the big-letters pad threw away a reply she had
    /// typed but not yet said.
    private var text: String { viewModel.typeToSpeakDraft }
    /// The typed sentence last said, kept after the field clears so it can
    /// be said again when the other person didn't catch it.
    @State private var lastTyped: String?
    @State private var editingPhrases = false
    @State private var showingBigText = false
    /// Read once a second while the screen is open: phrases are said on
    /// the media volume, which a deaf person's phone often keeps at zero,
    /// and Play then looks exactly as if it had worked.
    @State private var mediaVolume: Float?
    private var silentNotice: String? { mediaVolume.flatMap { SpeakingVolume.notice(outputVolume: $0) } }
    @FocusState private var isTyping: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// Stop and Play side by side, stacked when the text is too big to
    /// share one row.
    private var playButtonsLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 12))
            : AnyLayout(HStackLayout(spacing: 12))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 0) {
                        composer
                            .padding(16)

                        // On this leaf view, not the VStack that wraps the
                        // composer: putting it there made the VStack swallow
                        // the accessibility identifier of everything nested
                        // inside it, including "bigTextButton" -- XCUITest
                        // could no longer find it at all, even though it
                        // rendered fine.
                        Divider()
                            .accessibilityIdentifier("typeToSpeakScreen")
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }

                Section {
                    ForEach(viewModel.quickPhrases, id: \.self) { phrase in
                        Button {
                            viewModel.speak(phrase)
                        } label: {
                            HStack {
                                Text(phrase)
                                    .font(.title3)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Image(systemName: "speaker.wave.2")
                                    .foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .foregroundStyle(viewModel.canSay(phrase) ? .primary : .secondary)
                        .disabled(!viewModel.canSay(phrase))
                        .accessibilityLabel(phrase)
                        .accessibilityHint(tr("השמעה", "Play"))
                    }
                } header: {
                    HStack {
                        Text(tr("משפטים מוכנים", "Quick phrases"))
                            .accessibilityIdentifier("quickPhrasesHeader")
                        Spacer()
                        Button(editingPhrases ? tr("סיום", "Done") : tr("עריכה", "Edit")) {
                            editingPhrases.toggle()
                            // The keyboard covered the editor that just opened.
                            if editingPhrases { isTyping = false }
                        }
                            .font(.subheadline.weight(.semibold))
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                }

                if editingPhrases {
                    QuickPhrasesEditor(viewModel: viewModel)
                }
            }
            // Without edit mode the editor's rows had no delete or move
            // handles: reordering needed a hidden long-press drag, and
            // VoiceOver offered no way to move a phrase at all.
            .environment(\.editMode, .constant(editingPhrases ? .active : .inactive))
            // At the largest text sizes the composer fills the sheet, so
            // the whole sheet scrolls, and dragging it down puts the
            // keyboard away.
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(tr("להגיד משהו", "Say something"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("סגירה", "Close")) { dismiss() }
                }
            }
            .onAppear { isTyping = true }
            // Left to the big-letters view while it is up, so the warning
            // isn't announced twice.
            .watchingMediaVolume(while: !showingBigText) { mediaVolume = $0 }
            .announcing(silentNotice ?? "", whenTurningTrue: silentNotice != nil)
        }
        .presentationDetents([.medium, .large])
        .fullScreenCover(isPresented: $showingBigText) {
            BigTextView(
                text: $viewModel.typeToSpeakDraft,
                display: viewModel.display,
                canSpeak: viewModel.canSay(text),
                // lastTyped drives "Play again", so it has to track whatever
                // was actually spoken last, not just what speakTyped() sent
                // -- otherwise "Play again" can replay a stale, unrelated
                // phrase after this screen speaks a different one.
                onSpeak: { phrase in
                    if viewModel.speak(phrase) {
                        lastTyped = phrase
                    }
                }
            )
            .alertOverlay(for: viewModel)
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField(tr("הקלידו מה להגיד…", "Type what to say…"), text: $viewModel.typeToSpeakDraft, axis: .vertical)
                .font(.title2)
                .lineLimit(1...4)
                .focused($isTyping)
                .submitLabel(.send)
                .onSubmit(speakTyped)
                .textFieldStyle(.roundedBorder)

            // Stop is always there, dimmed until the phone talks: it used to
            // appear where Play had been, so a second tap on Play, by someone
            // who can't hear that the sentence had started, cut it off.
            playButtonsLayout {
                Button(action: speakTyped) {
                    Label(tr("השמעה", "Play"), systemImage: "speaker.wave.3.fill")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity)
                }
                .ozenGlassButton(prominent: true)
                .controlSize(.large)
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !viewModel.canSay(text))
                Button {
                    viewModel.stopSpeaking()
                } label: {
                    Label(tr("עצירה", "Stop"), systemImage: "stop.fill")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity)
                }
                .ozenGlassButton()
                .controlSize(.large)
                .disabled(!viewModel.isSpeaking)
                .accessibilityIdentifier("stopSpeakingButton")
            }

            if let silentNotice {
                MediaVolumeNotice(notice: silentNotice)
            }

            if let lastTyped {
                sayAgainRow(lastTyped)
            }

            Button {
                isTyping = false
                showingBigText = true
            } label: {
                Label(tr("מסך מלא באותיות גדולות", "Full screen, big letters"), systemImage: "textformat.size.larger")
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
            }
            .ozenGlassButton()
            .accessibilityHint(tr("כדי שמישהו יוכל לכתוב לכם, או כדי להראות למי שמולכם מה כתבתם", "Let someone write to you, or show the person you’re talking with what you wrote"))
            .accessibilityIdentifier("bigTextButton")

            if !viewModel.hasVoiceForAppLanguage {
                Label(MissingVoiceHint.appLanguage(viewModel.uiLanguage.nativeName), systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.readable(.orange))
            }
            if !viewModel.hasHebrewVoice {
                Label(MissingVoiceHint.hebrewShort(), systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.readable(.orange))
            }
        }
    }

    private func sayAgainRow(_ phrase: String) -> some View {
        HStack(spacing: 12) {
            Button {
                viewModel.speak(phrase)
            } label: {
                Label(phrase, systemImage: "arrow.counterclockwise")
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .ozenGlassButton()
            .disabled(!viewModel.canSay(phrase))
            .accessibilityLabel(tr("השמעה חוזרת: %1", "Play again: %1", args: ["\(phrase)"]))

            if !viewModel.quickPhrases.contains(phrase) {
                Button {
                    viewModel.addQuickPhrase(phrase)
                    Announcement.added(phrase)
                } label: {
                    Image(systemName: "plus.bubble")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .ozenGlassButton()
                .accessibilityLabel(tr("הוספה למשפטים המוכנים", "Add to quick phrases"))
            }
        }
    }

    private func speakTyped() {
        let phrase = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !phrase.isEmpty else { return }
        guard viewModel.speak(phrase) else { return }
        lastTyped = phrase
        // Left in the field, editable, rather than cleared: a typo caught
        // right after speaking otherwise meant retyping the whole
        // sentence. Matches the full-screen big-text pad, which never
        // clears itself either (see `BigTextView`).
    }
}

private struct QuickPhrasesEditor: View {
    @Bindable var viewModel: LiveCaptionViewModel
    @State private var newPhrase = ""
    @State private var confirmingReset = false

    var body: some View {
        Section(tr("עריכת המשפטים", "Edit phrases")) {
            HStack {
                TextField(tr("משפט חדש", "New phrase"), text: $newPhrase)
                    .onSubmit(add)
                Button(action: add) {
                    Image(systemName: "plus.circle.fill")
                        .font(.title2)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(tr("הוספה", "Add"))
                .disabled(newPhrase.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ForEach(viewModel.quickPhrases, id: \.self) { phrase in
                Text(phrase)
            }
            .onDelete { offsets in
                viewModel.removeQuickPhrases(at: offsets)
            }
            .onMove { from, to in
                viewModel.moveQuickPhrases(from: from, to: to)
            }
            Button(tr("שחזור ברירת המחדל", "Restore defaults"), role: .destructive) {
                confirmingReset = true
            }
            .disabled(viewModel.quickPhrases == AppSettings.defaultQuickPhrases(for: viewModel.uiLanguage))
            .confirmationDialog(tr("לחזור למשפטים המוכנים המקוריים?", "Restore the default phrases?"), isPresented: $confirmingReset, titleVisibility: .visible) {
                Button(tr("שחזור", "Restore"), role: .destructive) {
                    viewModel.resetQuickPhrases()
                }
                Button(tr("ביטול", "Cancel"), role: .cancel) {}
            } message: {
                Text(tr("המשפטים שנוספו או שונו יימחקו.", "Phrases that were added or changed will be deleted."))
            }
        }
    }

    private func add() {
        let phrase = newPhrase.trimmingCharacters(in: .whitespacesAndNewlines)
        let isNew = !phrase.isEmpty && !viewModel.quickPhrases.contains(phrase)
        viewModel.addQuickPhrase(newPhrase)
        newPhrase = ""
        if isNew { Announcement.added(phrase) }
    }
}
