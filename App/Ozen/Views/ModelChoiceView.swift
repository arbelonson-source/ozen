import SwiftUI
import OzenKit

struct ModelChoiceView: View {
    @Bindable var viewModel: LiveCaptionViewModel
    var onPick: (String) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var settings: AppSettings { viewModel.settings }

    var body: some View {
        VStack(spacing: 10) {
            ForEach(DeviceModelAdvisor.ordered(for: settings.spokenLanguage, on: settings.deviceModelCheck)) { option in
                row(for: option)
            }
            if let warning = slowPickWarning {
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(.readable(.orange))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("modelChoiceSlowWarning")
            }
        }
    }

    private var slowPickWarning: String? {
        guard let check = settings.deviceModelCheck,
              let chosen = WhisperModelCatalog.option(for: settings.whisperModelVariant),
              DeviceModelAdvisor.warns(about: DeviceModelAdvisor.fit(of: chosen, on: check))
        else { return nil }
        return tr("המודל הזה צפוי לפגר אחרי הדיבור בטלפון הזה, ולכן הכתוביות עלולות להגיע באיחור. מודל קל יותר מדביק את הקצב טוב יותר.", "This model is likely to fall behind the speech on this phone, so captions may arrive late. A lighter model keeps up better.")
    }

    private var nameLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 8))
    }

    private func row(for option: WhisperModelOption) -> some View {
        let isSelected = option.variant == settings.whisperModelVariant
        let isRecommended = option.variant == settings.recommendedWhisperVariant
        return Button {
            onPick(option.variant)
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top, spacing: 8) {
                    nameLayout {
                        Text(option.displayName)
                            .font(.body.weight(isSelected ? .semibold : .regular))
                        if isRecommended {
                            RecommendedBadge(forThisPhone: settings.deviceModelCheck != nil, language: settings.spokenLanguage)
                        }
                    }
                    Spacer(minLength: 0)
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .accessibilityHidden(true)
                        .foregroundStyle(isSelected ? AnyShapeStyle(ReadableColor.readable(.green)) : AnyShapeStyle(.secondary))
                }
                Text(option.sizeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let check = settings.deviceModelCheck {
                    FitLabel(fit: DeviceModelAdvisor.fit(of: option, on: check))
                }
                Text(option.localizedNote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(isSelected ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("modelChoiceRow-\(option.variant)")
    }
}

struct RecommendedBadge: View {
    let forThisPhone: Bool
    let language: UILanguage

    var body: some View {
        Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(0.18), in: Capsule())
            .fixedSize(horizontal: false, vertical: true)
    }

    private var label: String {
        if forThisPhone { return tr("מומלץ לטלפון הזה", "Recommended for this phone") }
        return language == .hebrew ? tr("מומלץ לעברית", "Recommended for Hebrew") : tr("מומלץ", "Recommended")
    }
}

struct FitLabel: View {
    let fit: ModelFit

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: symbol)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.readable(color))
        .fixedSize(horizontal: false, vertical: true)
    }

    private var text: String {
        switch fit {
        case .keepsUp: return tr("עומד בקצב", "Keeps up")
        case .aLittleBehind: return tr("מפגר קצת", "A little behind")
        case .tooSlow: return tr("איטי מדי בטלפון הזה", "Too slow on this phone")
        }
    }

    private var symbol: String {
        switch fit {
        case .keepsUp: return "checkmark.circle"
        case .aLittleBehind: return "clock"
        case .tooSlow: return "exclamationmark.triangle"
        }
    }

    private var color: Color {
        switch fit {
        case .keepsUp: return .green
        case .aLittleBehind: return .orange
        case .tooSlow: return .red
        }
    }
}

extension WhisperModelOption {
    var localizedNote: String {
        switch variant {
        case "tiny": return tr("הכי מהיר. העברית שלו בעיקר שגויה — רק לבדיקת המיקרופון.", "The fastest. Its Hebrew is mostly wrong — for testing the microphone only.")
        case "base": return tr("מהיר מאוד, עדיין חלש בעברית.", "Very fast, still weak at Hebrew.")
        case "small_216MB": return tr("חצי מההורדה של Small עם כמעט אותן תוצאות.", "Half the download of Small with nearly the same results.")
        case "small": return tr("הורדה קצרה ותגובה מהירה. עברית מובנת, עם טעויות.", "A short download and a quick response. Hebrew is understandable, with mistakes.")
        case "ozen-turbo-hebrew-a3-8bit": return tr("Turbo Hebrew שאוזן אימנה עוד על דיבור מקצה החדר, ברעש וליד טלוויזיה: בערך עשירית פחות מילים שגויות שם, ואותו דבר מקרוב. מהיר באותה מידה, אותה הורדה.", "Turbo Hebrew trained further by Ozen on speech from across a room, in noise and over a TV: about a tenth fewer wrong words there, the same up close. As quick, the same download.")
        case "ivrit-large-v3-turbo-8bit": return tr("Turbo שאומן על כ-5,000 שעות של עברית על ידי ivrit.ai: שליש פחות מילים שגויות מ-Turbo בהקלטות בדיקה. מהיר באותה מידה, הורדה גדולה יותר.", "Turbo trained on about 5,000 hours of Hebrew by ivrit.ai: a third fewer wrong words than Turbo on test recordings. As quick, a bigger download.")
        case "large-v3-v20240930_626MB": return tr("עברית טובה בהרבה מ‑Small באותו גודל הורדה בערך. קצת יותר איטי בכל עדכון.", "Much better Hebrew than Small at roughly the same download size. A bit slower on each update.")
        case "large-v3-v20240930": return tr("Turbo בדיוק מלא. אותה רמת דיוק, הורדה גדולה יותר.", "Turbo at full precision. Same accuracy, a larger download.")
        case "medium": return tr("מודל ביניים ישן יותר; Turbo גם מדויק יותר וגם מהיר יותר.", "An older mid-size model; Turbo is both more accurate and faster.")
        case "large-v3_947MB": return tr("פחות מדויק בעברית מ‑Turbo Hebrew, ואיטי מדי כדי להרגיש \"חי\" בטלפון.", "Less accurate in Hebrew than Turbo Hebrew, and too slow to feel “live” on the phone.")
        case "large-v3": return tr("3 GB. פחות מדויק בעברית מ‑Turbo Hebrew, והכי איטי; להשוואה בלבד.", "3 GB. Less accurate in Hebrew than Turbo Hebrew, and the slowest; for comparison only.")
        default: return note
        }
    }
}
