import Testing
@testable import OzenKit

@Suite("Where the voice hint sends someone with no voice installed")
struct MissingVoiceHintTests {
    struct Path: Sendable {
        let language: UILanguage
        let older: String
        let iOS26: String
    }

    static let paths: [Path] = [
        Path(language: .english, older: "Accessibility → Spoken Content → Voices", iOS26: "Accessibility → Read & Speak → Voices"),
        Path(language: .hebrew, older: "נגישות ← הקראת תוכן ← קולות", iOS26: "נגישות ← קריאה ודיבור ← קולות"),
        Path(language: .amharic, older: "Accessibility → Spoken Content → Voices", iOS26: "Accessibility → Read & Speak → Voices"),
        Path(language: .arabic, older: "تسهيلات الاستخدام ← المحتوى المنطوق ← الأصوات", iOS26: "تسهيلات الاستخدام ← القراءة والنطق ← الأصوات"),
        Path(language: .chineseSimplified, older: "辅助功能 → 朗读内容 → 声音", iOS26: "辅助功能 → 阅读与朗读 → 声音"),
        Path(language: .french, older: "Accessibilité → Contenu énoncé → Voix", iOS26: "Accessibilité → Lire et énoncer → Voix"),
        Path(language: .german, older: "Bedienungshilfen → Gesprochene Inhalte → Stimmen", iOS26: "Bedienungshilfen → Lesen & Sprechen → Stimmen"),
        Path(language: .hindi, older: "ऐक्सेसिबिलिटी → बोले गए कॉन्टेंट → वॉइस", iOS26: "ऐक्सेसिबिलिटी → पढ़ें और बोलें → वॉइस"),
        Path(language: .portuguese, older: "Acessibilidade → Conteúdo enunciado → Vozes", iOS26: "Acessibilidade → Ler e falar → Vozes"),
        Path(language: .russian, older: "Универсальный доступ → Устный контент → Голоса", iOS26: "Универсальный доступ → Чтение вслух → Голоса"),
        Path(language: .spanish, older: "Accesibilidad → Contenido leído → Voces", iOS26: "Accesibilidad → Lectura y voz → Voces"),
        Path(language: .ukrainian, older: "Доступність → Усний вміст → Голоси", iOS26: "Доступність → Читання і мовлення → Голоси"),
    ]

    static func hints(readAndSpeak: Bool) -> [String] {
        [
            MissingVoiceHint.appLanguage("Français", readAndSpeak: readAndSpeak),
            MissingVoiceHint.hebrew(readAndSpeak: readAndSpeak),
            MissingVoiceHint.hebrewShort(readAndSpeak: readAndSpeak),
        ]
    }

    @Test("iOS 26 names the menu Read & Speak, as Apple's guide does in each language", arguments: paths)
    func iOS26(path: Path) {
        Localization.$override.withValue(path.language) {
            for hint in Self.hints(readAndSpeak: true) {
                #expect(hint.contains(path.iOS26), "\(path.language): \(hint)")
            }
        }
    }

    @Test("iOS 17 and 18 still name it Spoken Content, as it reads there", arguments: paths)
    func older(path: Path) {
        Localization.$override.withValue(path.language) {
            for hint in Self.hints(readAndSpeak: false) {
                #expect(hint.contains(path.older), "\(path.language): \(hint)")
            }
        }
    }

    @Test("the hint names the missing language")
    func namesTheLanguage() {
        Localization.$override.withValue(.english) {
            #expect(MissingVoiceHint.appLanguage("Français", readAndSpeak: true).hasPrefix("No voice for Français is installed"))
        }
    }
}
