import Foundation
import OzenKit
import SwiftUI
import UIKit

enum SpeechLanguageAttribute: AttributedStringKey {
    typealias Value = String
    static let name = NSAttributedString.Key.accessibilitySpeechLanguage.rawValue
}

extension AttributeScopes {
    struct CaptionSpeechAttributes: AttributeScope {
        let speechLanguage: SpeechLanguageAttribute
    }
}

extension AttributeDynamicLookup {
    subscript<T: AttributedStringKey>(dynamicMember keyPath: KeyPath<AttributeScopes.CaptionSpeechAttributes, T>) -> T {
        self[T.self]
    }
}

enum CaptionSpeechText {
    static func label(before: String, caption: String, after: String = "", languageCode: String) -> AttributedString {
        var spoken = AttributedString(before)
        var line = AttributedString(caption)
        line[SpeechLanguageAttribute.self] = CaptionSpeechLanguage.bcp47(forLine: caption, languageCode: languageCode)
        spoken.append(line)
        spoken.append(AttributedString(after))
        return spoken
    }

    static func label(whole: String, endingWith caption: String, languageCode: String) -> AttributedString {
        guard whole.hasSuffix(caption) else { return AttributedString(whole) }
        return label(before: String(whole.dropLast(caption.count)), caption: caption, languageCode: languageCode)
    }

    static func announcement(before: String = "", caption: String, languageCode: String) -> NSAttributedString {
        let spoken = NSMutableAttributedString(string: before + caption)
        let range = NSRange(location: (before as NSString).length, length: (caption as NSString).length)
        spoken.addAttribute(.accessibilitySpeechLanguage, value: CaptionSpeechLanguage.bcp47(forLine: caption, languageCode: languageCode), range: range)
        return spoken
    }
}
