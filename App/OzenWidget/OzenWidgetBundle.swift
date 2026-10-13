import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct OzenWidgetBundle: WidgetBundle {
    var body: some Widget {
        CaptionLiveActivity()
        if #available(iOS 18.0, *) {
            StartCaptionsControl()
        }
    }
}

/// A button for Control Center, or in place of the flashlight or camera on
/// the lock screen, that opens Ozen and starts captions: one press when
/// someone starts talking, instead of finding the app.
@available(iOS 18.0, *)
struct StartCaptionsControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.arbelonson.ozen.Ozen.Captions.start") {
            ControlWidgetButton(action: StartCaptionsIntent()) {
                Label("כתוביות", systemImage: "captions.bubble.fill")
            }
        }
        .displayName("התחלת כתוביות")
        .description("פותח את אוזן ומתחיל להציג כתוביות לשיחה.")
    }
}

/// The newest caption lines on the lock screen and in the Dynamic Island,
/// so she can read the last sentence without unlocking the phone.
struct CaptionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CaptionActivityAttributes.self) { context in
            CaptionLinesView(state: context.state, isStale: context.isStale, fontSize: context.state.large ? 27 : 21)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .activityBackgroundTint(.black)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    IslandMark(state: context.state, isStale: context.isStale)
                        .font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    IslandTalking(state: context.state, isStale: context.isStale)
                        .font(.title2)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    CaptionLinesView(state: context.state, isStale: context.isStale, fontSize: 17, island: true)
                }
            } compactLeading: {
                // Named like the minimal mark, so VoiceOver finds the app
                // and why the lines stopped, if they did.
                IslandMark(state: context.state, isStale: context.isStale)
            } compactTrailing: {
                IslandNewestWords(state: context.state, isStale: context.isStale)
            } minimal: {
                IslandMark(state: context.state, isStale: context.isStale)
            }
        }
    }
}

extension CaptionActivityAttributes.ContentState.Line {
    /// The app starts a line with a right-to-left mark when it reads from
    /// the right (`CaptionLayout.directed`). Laid out right to left whatever
    /// they said, English captions sat against the right edge.
    var readsRightToLeft: Bool {
        text.hasPrefix("\u{200F}")
    }
}

extension CaptionActivityAttributes.ContentState {
    /// The app sends it in its own language; a state from an older app has none.
    var notUpdatingNote: String {
        notUpdating ?? (english ? "Captions aren’t updating. Open Ozen." : "הכתוביות לא מתעדכנות. פתחו את אוזן.")
    }
}

/// The small island's mark. Stopped, paused or no longer updating, it turns
/// into an orange warning that VoiceOver reads with the note: the same
/// yellow mark as when captions run said all was well.
struct IslandMark: View {
    let state: CaptionActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        let name = state.appName ?? (state.english ? "Ozen" : "אוזן")
        let note = isStale ? state.notUpdatingNote : state.status
        Image(systemName: note == nil ? "captions.bubble.fill" : "exclamationmark.bubble.fill")
            .foregroundStyle(note == nil ? Color.yellow : Color.orange)
            .accessibilityLabel(note.map { "\(name). \($0)" } ?? name)
    }
}

/// The end of the newest line beside the camera, so someone talking shows
/// without holding the island open or going back to the app. Quiet for a
/// minute, stopped or no longer updating, the ear instead: words left
/// there would read as just said.
struct IslandNewestWords: View {
    let state: CaptionActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        if !isStale, state.status == nil, state.ageNote == nil, let line = state.lines.last {
            Text(Self.newestWords(of: line))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(line.isFinal ? Color.white : Color.white.opacity(0.7))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                // A single word too long for the room still shows its end.
                .truncationMode(.head)
                .frame(maxWidth: 64)
                // Laid out left to right, a Hebrew line lost its last letter
                // at the edge of its room.
                .environment(\.layoutDirection, line.readsRightToLeft ? .rightToLeft : .leftToRight)
        } else {
            Image(systemName: "ear")
                .accessibilityHidden(true)
        }
    }
}

extension IslandNewestWords {
    /// About this many letters fit beside the camera.
    static let room = 10

    /// The last whole words of the line that fit, after an ellipsis when
    /// some were left out: cut by the letter, it showed "…ot sure if".
    static func newestWords(of line: CaptionActivityAttributes.ContentState.Line) -> String {
        let words = line.text.replacingOccurrences(of: "\u{200F}", with: "").split(separator: " ")
        var picked: [Substring] = []
        var length = 0
        for word in words.reversed() {
            let longer = length + word.count + (picked.isEmpty ? 0 : 1)
            if longer > room, !picked.isEmpty { break }
            picked.insert(word, at: 0)
            length = longer
        }
        let shown = (picked.count < words.count ? "…" : "") + picked.joined(separator: " ")
        return line.readsRightToLeft ? "\u{200F}" + shown : shown
    }
}

/// Held open, beside the camera: a waveform while a line is still being
/// written, so she can tell someone is talking right now.
struct IslandTalking: View {
    let state: CaptionActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        if !isStale, state.status == nil, state.ageNote == nil, let line = state.lines.last, !line.isFinal {
            Image(systemName: "waveform")
                .foregroundStyle(.white)
                // The line below is read out, dimmer words and all.
                .accessibilityHidden(true)
        }
    }
}

/// White text on black, like the caption screen: a line still being
/// written is dimmer, and a name heads a speaker's run in yellow.
struct CaptionLinesView: View {
    let state: CaptionActivityAttributes.ContentState
    let isStale: Bool
    let fontSize: CGFloat
    /// In the Dynamic Island, held open: less room than the lock screen,
    /// so a line above the newest keeps to one row.
    var island = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if state.lines.isEmpty {
                // "Listening" under "captions are starting", "paused
                // because of a call" or "captions aren't updating" would
                // say the opposite of the note.
                if state.status == nil && !isStale {
                    listeningLabel
                }
            } else {
                ForEach(Array(shownLines.enumerated()), id: \.offset) { offset, line in
                    let isNewest = offset == shownLines.count - 1
                    lineText(line)
                        .font(.system(size: fontSize, weight: .semibold))
                        // The app cuts each line to about this many lines
                        // (`LockScreenCaptions`); a wide one shrinks a little
                        // rather than lose its end, the newest words. One
                        // still too long (the newest line takes the room a
                        // short line above leaves) loses its start instead,
                        // as the app's own cut does.
                        .lineLimit(isNewest ? 3 : (state.large || island ? 1 : 2))
                        .truncationMode(isNewest ? .head : .tail)
                        .minimumScaleFactor(0.8)
                        .fixedSize(horizontal: false, vertical: true)
                        .inAppDirection(line.readsRightToLeft ? .rightToLeft : .leftToRight)
                }
            }
            if isStale {
                // The app stopped updating the lines (iOS closed it, say):
                // they may be long out of date, and so may a note it left
                // ("paused for a call" with the call long over).
                note(state.notUpdatingNote)
            } else if let status = state.status {
                note(status)
            } else if let ageNote = state.ageNote {
                // Captions run, but nobody has spoken for a while.
                note(ageNote, color: .white.opacity(0.7))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A note under the lines: why they stopped coming, or how long ago
    /// they were said. Follows the phone's text size, up to
    /// where it would crowd the lines out of the lock screen's room.
    private func note(_ text: String, color: Color = .orange) -> some View {
        Text(text)
            .font(.system(.subheadline, weight: .semibold))
            .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            .foregroundStyle(color)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .inAppDirection(appDirection)
    }

    /// Each caption line reads the way its own words do, whatever the app's
    /// language; the app's own words follow the app's language.
    private var appDirection: LayoutDirection {
        (state.appRightToLeft ?? !state.english) ? .rightToLeft : .leftToRight
    }

    private var listeningLabel: some View {
        Label(state.listening ?? (state.english ? "Listening…" : "מקשיב…"), systemImage: "ear")
            .font(.system(size: fontSize, weight: .semibold))
            .foregroundStyle(.white.opacity(0.8))
            .inAppDirection(appDirection)
    }

    /// Only the newest line under a note: two caption lines fill the lock
    /// screen's room, leaving none for it.
    private var shownLines: [CaptionActivityAttributes.ContentState.Line] {
        guard state.status != nil || isStale, var newest = state.lines.last else {
            return state.lines
        }
        if newest.speaker == nil, newest.sameSpeakerAsAbove == true, state.lines.count > 1 {
            // The name was left off because the line above has it; a line
            // nobody was recognised on stays without one.
            newest.speaker = state.lines[state.lines.count - 2].speaker
        }
        return [newest]
    }

    private func lineText(_ line: CaptionActivityAttributes.ContentState.Line) -> Text {
        let words = Text(line.text)
            .foregroundColor(line.isFinal ? .white : .white.opacity(0.7))
        guard let speaker = line.speaker else { return words }
        // The line's own direction mark goes in front of the name: a name in
        // Latin letters ("Avi") first would set the paragraph left to right.
        let mark = line.readsRightToLeft ? "\u{200F}" : ""
        return Text("\(mark)\(speaker): ").foregroundColor(.yellow) + words
    }
}

private extension View {
    func inAppDirection(_ direction: LayoutDirection) -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.layoutDirection, direction)
    }
}
