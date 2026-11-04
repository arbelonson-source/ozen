import SwiftUI
import OzenKit

/// One utterance. `isCommitted` drives the only visual difference that
/// matters here: committed text is at full strength and permanent, pending
/// text is dimmer to signal "still settling" — nothing is ever truncated in
/// either state. Only the colour changes, never the slant or weight: Hebrew
/// has no italic, so iOS would skew the letters of the very line being read,
/// and a change of weight re-wraps the line at the moment it locks in.
struct CaptionRow: View {
    let segment: TranscriptSegment
    let speakerName: String?
    var speakerSlots: SpeakerColorSlots?
    let showsSpeakerLabel: Bool
    let display: DisplayPreferences
    let theme: CaptionTheme
    let isKeywordHit: Bool
    let isStarred: Bool
    let isUncertain: Bool
    var marksUncertainWords = false
    /// The spoken language's code, for a line with no letters of its own.
    var languageCode = "he"
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if showsSpeakerLabel, let speakerName {
                HStack(spacing: 6) {
                    Text(speakerName)
                        .font(.system(size: max(15, display.fontSize * 0.5), weight: .semibold))
                    Circle()
                        .frame(width: 10, height: 10)
                }
                .foregroundStyle(SpeakerColor.color(forClusterID: segment.speakerClusterID, speakerName: speakerName, slots: speakerSlots, on: theme.colorScheme))
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if isStarred {
                    Image(systemName: "star.fill")
                        .font(.system(size: max(14, display.fontSize * 0.6)))
                        .foregroundStyle(Color.yellow.readable(on: theme.colorScheme))
                }
                if isKeywordHit {
                    // The yellow field alone is easy to miss scrolling back
                    // with weak eyes; a bell marks the line the way the star
                    // and question mark mark theirs.
                    Image(systemName: "bell.fill")
                        .font(.system(size: max(14, display.fontSize * 0.6)))
                        .foregroundStyle(theme.text)
                }
                if isUncertain {
                    // Not a colour change: the words stay as readable as
                    // every other line, with a mark saying they may be wrong.
                    Image(systemName: "questionmark.circle")
                        .font(.system(size: max(14, display.fontSize * 0.6)))
                        .foregroundStyle(theme.pendingText)
                }
                Text(
                    caption: CaptionLayout.displayText(segment.text, languageCode: languageCode),
                    emphasizingNumbers: display.emphasizeNumbers,
                    size: display.fontSize,
                    // A line still being written keeps its dimmer colour
                    // throughout, numbers included, so it reads as unfinished.
                    numberColor: segment.isCommitted ? theme.numberText : nil,
                    uncertainWords: marksUncertainWords && segment.isCommitted ? segment.uncertainWords : [],
                    // Only once the engine says the line is final: a
                    // number still being heard can change under her finger.
                    linkingPhoneNumbers: segment.isSettled
                )
                    .font(.system(size: display.fontSize, weight: display.boldText ? .bold : .medium))
                    .foregroundStyle(segment.isCommitted ? theme.text : theme.pendingText)
                    // A tappable number takes the theme's own number colour,
                    // not the system blue, which is dim on the light theme.
                    .tint(theme.numberText)
                    .multilineTextAlignment(.leading)
                    .lineSpacing(display.fontSize * 0.15)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, isKeywordHit ? 8 : 0)
                    .padding(.vertical, isKeywordHit ? 4 : 0)
                    .background(
                        // A keyword line keeps a soft yellow field behind it,
                        // so the reader can find "where my name was said"
                        // after the buzz, even a screenful later.
                        isKeywordHit ? Color.yellow.opacity(theme.colorScheme == .dark ? 0.22 : 0.35) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Each line reads the way its own words do, whatever language the
        // buttons around it are in: Hebrew from the right, English from
        // the left.
        .environment(\.layoutDirection, CaptionLayout.readsRightToLeft(segment.text, languageCode: languageCode) ? .rightToLeft : .leftToRight)
        .accessibilityElement(children: .ignore)
        // VoiceOver reads the speaker on every line, even where the
        // screen leaves the repeated name out.
        .accessibilityLabel(Text(accessibilityLabelText))
        .accessibilityIdentifier("captionRow")
        .accessibilityHint(isKeywordHit ? tr("מכילה מילה חשובה", "Contains an important word") : "")
        // The line is one element for VoiceOver, so the tappable number in
        // it is offered as an action instead.
        .accessibilityActions {
            if segment.isSettled {
                ForEach(PhoneNumbers.matches(in: segment.text), id: \.dialable) { phone in
                    if let url = phone.url {
                        Button(tr("להתקשר ל־%1", "Call %1", args: ["\(phone.dialable)"])) { openURL(url) }
                    }
                }
            }
        }
    }

    private var accessibilityLabelText: AttributedString {
        var before = ""
        if isStarred { before += tr("מסומן כחשוב. ", "Marked as important. ") }
        if isUncertain { before += tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") }
        if let speakerName { before += "\(speakerName): " }
        var after = ""
        let doubtful = UncertainWords.spoken(in: segment.text, words: marksUncertainWords && segment.isCommitted ? segment.uncertainWords : [])
        if !doubtful.isEmpty {
            after = " " + tr("מילים שאולי לא נשמעו נכון: %1", "Words that may be wrong: %1", args: ["\(doubtful.joined(separator: ", "))"])
        }
        return CaptionSpeechText.label(before: before, caption: segment.text, after: after, languageCode: languageCode)
    }
}

struct NameSpeakerSheet: View {
    let segment: TranscriptSegment
    let viewModel: LiveCaptionViewModel
    @State private var name = ""
    @FocusState private var typing: Bool
    @Environment(\.dismiss) private var dismiss

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    private var canSave: Bool { !trimmedName.isEmpty && segment.speakerClusterID != nil }

    /// Someone already saved, split off as a new "speaker 3": one tap puts
    /// the right name back instead of typing it again.
    private var savedNames: [String] {
        SavedSpeaker.grouping(viewModel.settings.speakerProfiles).map(\.name)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(CaptionLayout.displayText(segment.text))
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                } header: {
                    Text(tr("מי אמר את זה?", "Who said this?"))
                }
                Section {
                    TextField(tr("שם", "Name"), text: $name)
                        .textInputAutocapitalization(.words)
                        .focused($typing)
                        .submitLabel(.done)
                        .onSubmit { if canSave { save() } }
                } footer: {
                    Text(segment.speakerClusterID == nil
                         ? tr("עדיין לא זוהה קול לשורה הזו. נסו שוב אחרי שידברו עוד קצת.", "No voice has been identified for this line yet. Try again after the person talks a bit more.")
                         : tr("מעכשיו כל מה שהקול הזה יגיד יופיע עם השם הזה.", "From now on, everything this voice says will show with this name."))
                }
                if segment.speakerClusterID != nil, !savedNames.isEmpty {
                    Section(tr("דוברים שמורים", "Saved speakers")) {
                        ForEach(savedNames, id: \.self) { saved in
                            Button {
                                name = saved
                                save()
                            } label: {
                                Label(saved, systemImage: "person.wave.2")
                            }
                        }
                    }
                }
            }
            .navigationTitle(tr("שם לדובר", "Name the speaker"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("שמירה", "Save"), action: save)
                        .disabled(!canSave)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("ביטול", "Cancel")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            // Focus asked for while the sheet is still sliding up is often
            // dropped: wait for it to settle.
            try? await Task.sleep(for: .milliseconds(400))
            typing = segment.speakerClusterID != nil && savedNames.isEmpty
        }
    }

    private func save() {
        viewModel.nameSpeaker(of: segment, name: trimmedName)
        dismiss()
    }
}

/// The sound-event banner: big icon, the Hebrew name, importance colour.
/// Tapping dismisses. Critical alerts (sirens, smoke detector) are red and
/// stay longer; everything else is calm.
struct SoundAlertBanner: View {
    let alert: SoundAlert
    let onDismiss: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var rung = false

    var body: some View {
        Button(action: onDismiss) {
            HStack(spacing: 14) {
                // One hop as it arrives, to catch the eye.
                Image(systemName: alert.event.systemImage)
                    .font(.system(size: 30, weight: .semibold))
                    .symbolEffect(.bounce, value: rung)
                    .onAppear { if !reduceMotion { rung = true } }
                VStack(alignment: .leading, spacing: 2) {
                    Text(alert.event.name)
                        .font(.title3.weight(.bold))
                    Text(alert.event.importance == .critical ? tr("שימו לב!", "Attention!") : tr("נשמע עכשיו", "Heard just now"))
                        .font(.subheadline)
                        .opacity(BannerPalette.subtitleOpacity)
                }
                Spacer()
                Image(systemName: "xmark")
                    .font(.headline)
                    .opacity(0.7)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            // White on the system orange of a doorbell alert is under 3:1;
            // the deeper shade keeps the words readable at a glance.
            .background(SoundAlertsView.tint(alert.event.importance).deepShade.opacity(BannerPalette.fillAlpha), in: RoundedRectangle(cornerRadius: 16))
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tr("התראה: %1", "Alert: %1", args: ["\(alert.event.name)"]))
        .accessibilityHint(tr("הקישו לסגירה", "Tap to close"))
    }
}

/// Flashes the edge of the whole screen when a safety or door sound is
/// heard (see `AlertFlash`), so it's noticed without looking at the
/// banner, and in place of a buzz the device can't give (`standIn`: her
/// name, or talk starting again, on an iPad, which has no vibration
/// motor). Only new alerts flash, not one that was already there when the
/// screen appeared. Takes no touches, and is invisible when dark.
struct AlertFlashOverlay: View {
    let alert: SoundAlert?
    var standIn: BuzzStandIn? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flashing: Run?
    /// Kept after a flash ends, so its last fade doesn't change colour.
    @State private var tint = Tint.sound(.critical)
    @State private var isLit = false

    private enum Tint: Equatable {
        case sound(SoundEvent.Importance)
        /// Her name or talk heard, in the yellow of the name's pill.
        case heard
    }

    /// One alert's flashing. A stand-in for a buzz has no importance: it
    /// never cuts a sound's flash short, and any sound that flashes cuts
    /// it short.
    private struct Run: Equatable {
        let id: UUID
        let flash: AlertFlash
        let tint: Tint
        let importance: SoundEvent.Importance?
    }

    private var colour: Color {
        switch tint {
        case .sound(let importance): SoundAlertsView.tint(importance)
        case .heard: .yellow
        }
    }

    var body: some View {
        Rectangle()
            .strokeBorder(colour, lineWidth: 22)
            .background(colour.opacity(0.2))
            .ignoresSafeArea()
            .opacity(isLit ? 1 : 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onChange(of: alert?.id) { _, _ in
                // A kettle, or the door, heard during a siren's flash doesn't
                // cut it short.
                guard let alert,
                      AlertFlash.takesOver(from: flashing?.importance, with: alert.event.importance, reduceMotion: reduceMotion),
                      let flash = AlertFlash.pattern(for: alert.event.importance, reduceMotion: reduceMotion)
                else { return }
                start(Run(id: alert.id, flash: flash, tint: .sound(alert.event.importance), importance: alert.event.importance))
            }
            .onChange(of: standIn?.id) { _, _ in
                guard let standIn, flashing?.importance == nil,
                      let flash = AlertFlash.insteadOfBuzz(for: standIn.vibration, reduceMotion: reduceMotion)
                else { return }
                start(Run(id: standIn.id, flash: flash, tint: .heard, importance: nil))
            }
            .task(id: flashing?.id) {
                guard let run = flashing else { return }
                let flash = run.flash
                // A newer alert cancels this run, but a cancelled task still
                // wakes up: only the run for the alert still flashing may
                // touch the light, or this one's "off" could land on the
                // newer one's first flash.
                let id = run.id
                // Over, or the screen went away: the next alert flashes
                // whatever it is, and coming back doesn't replay this one.
                defer { if flashing?.id == id { flashing = nil } }
                for _ in 0..<flash.count {
                    guard flashing?.id == id else { return }
                    withAnimation(.easeOut(duration: 0.08)) { isLit = true }
                    try? await Task.sleep(for: .seconds(flash.litSeconds))
                    guard flashing?.id == id else { return }
                    withAnimation(.easeIn(duration: 0.15)) { isLit = false }
                    if Task.isCancelled { return }
                    try? await Task.sleep(for: .seconds(flash.darkSeconds))
                    if Task.isCancelled { return }
                }
            }
    }

    private func start(_ run: Run) {
        flashing = run
        tint = run.tint
    }
}

/// A small, quiet confirmation that a keyword was heard, so the buzz has
/// a visible explanation.
struct KeywordHitPill: View {
    let hit: KeywordHit
    let speakerName: String?
    let onDismiss: () -> Void

    var body: some View {
        Button(action: onDismiss) {
            Label(text, systemImage: "text.badge.star")
                .font(.headline)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.yellow.opacity(0.9), in: Capsule())
                .foregroundStyle(.black)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(text)
        .accessibilityHint(tr("הקישו לסגירה", "Tap to close"))
        .accessibilityIdentifier("keywordHitPill")
    }

    // A dash, not "X said" -- Hebrew's "said" is gendered and speakers
    // aren't tracked by gender.
    private var text: String {
        KeywordPill.text(for: hit, speaker: speakerName)
    }
}
