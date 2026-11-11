import Testing
@testable import OzenKit

@Suite("Where the flash-alerts hints send someone, on each iOS")
struct FlashAlertsHintTests {
    struct Names: Sendable {
        let language: UILanguage
        let olderAudioVisual: String
        let olderSwitch: String
        let audioVisual: String
        let flashSwitch: String
        let ledChoice: String

        var arrow: String { language == .hebrew || language == .arabic ? " ← " : " → " }
    }

    static let names: [Names] = [
        Names(language: .english, olderAudioVisual: "Audio & Visual", olderSwitch: "LED Flash for Alerts", audioVisual: "Audio & Visual", flashSwitch: "Flash for Alerts", ledChoice: "LED Flash"),
        Names(language: .hebrew, olderAudioVisual: "שמיעה וראיה", olderSwitch: "הבהוב LED בעת התראה", audioVisual: "שמע וחזות", flashSwitch: "התראות בהבהוב", ledChoice: "הבהוב LED"),
        Names(language: .amharic, olderAudioVisual: "Audio & Visual", olderSwitch: "LED Flash for Alerts", audioVisual: "Audio & Visual", flashSwitch: "Flash for Alerts", ledChoice: "LED Flash"),
        Names(language: .arabic, olderAudioVisual: "الصوت والصورة", olderSwitch: "وميض LED للتنبيهات", audioVisual: "الصوت والصورة", flashSwitch: "الوميض للتنبيهات", ledChoice: "وميض LED"),
        Names(language: .chineseSimplified, olderAudioVisual: "音频与视觉", olderSwitch: "LED 闪烁以示提醒", audioVisual: "音频与视觉", flashSwitch: "闪烁以示提醒", ledChoice: "LED 闪烁"),
        Names(language: .french, olderAudioVisual: "Audio et Visuel", olderSwitch: "Flash LED pour alertes", audioVisual: "Audio et visuel", flashSwitch: "Clignotement pour les alertes", ledChoice: "Flash LED"),
        Names(language: .german, olderAudioVisual: "Audio & Visuelles", olderSwitch: "LED-Blitz bei Hinweisen", audioVisual: "Audio & Visuelles", flashSwitch: "Bei Hinweisen blinken", ledChoice: "LED-Blitz"),
        Names(language: .hindi, olderAudioVisual: "ऑडियो और विज़ुअल", olderSwitch: "अलर्ट के लिए LED फ़्लैश", audioVisual: "ऑडियो और विज़ुअल", flashSwitch: "अलर्ट के लिए फ़्लैश करें", ledChoice: "LED फ़्लैश"),
        Names(language: .portuguese, olderAudioVisual: "Áudio e visual", olderSwitch: "Avisos com flash LED", audioVisual: "Áudio e visual", flashSwitch: "Avisos por intermitência", ledChoice: "Flash LED"),
        Names(language: .russian, olderAudioVisual: "Аудиовизуализация", olderSwitch: "Вспышка для предупреждений", audioVisual: "Аудиовизуализация", flashSwitch: "Мигание при предупреждениях", ledChoice: "LED‑вспышка"),
        Names(language: .spanish, olderAudioVisual: "Audio y efectos visuales", olderSwitch: "Avisos LED intermitentes", audioVisual: "Audio y efectos visuales", flashSwitch: "Parpadeo para los avisos", ledChoice: "Flash LED"),
        Names(language: .ukrainian, olderAudioVisual: "Аудіювання і візуалізація", olderSwitch: "Спалах для оповісток", audioVisual: "Аудіювання і візуалізація", flashSwitch: "Блимання для оповісток", ledChoice: "LED-спалах"),
    ]

    @Test("iOS 26 names Flash for Alerts and its LED choice, as Apple's guide does in each language", arguments: names)
    func iOS26(names: Names) {
        Localization.$override.withValue(names.language) {
            let footer = FlashAlertsHint.settingsFooter(flashForAlerts: true)
            let path = [names.audioVisual, names.flashSwitch, names.ledChoice].joined(separator: names.arrow)
            #expect(footer.contains(path), "\(names.language): \(footer)")
            let night = FlashAlertsHint.nightChecklist(flashForAlerts: true)
            for name in [names.audioVisual, names.flashSwitch, names.ledChoice] {
                #expect(night.contains(name), "\(names.language) lacks \(name): \(night)")
            }
            #expect(!night.contains(names.olderSwitch), "\(names.language): \(night)")
        }
    }

    @Test("iOS 17 and 18 have one switch, LED Flash for Alerts, as it reads there", arguments: names)
    func older(names: Names) {
        Localization.$override.withValue(names.language) {
            let footer = FlashAlertsHint.settingsFooter(flashForAlerts: false)
            let path = [names.olderAudioVisual, names.olderSwitch].joined(separator: names.arrow)
            #expect(footer.contains(path), "\(names.language): \(footer)")
            let night = FlashAlertsHint.nightChecklist(flashForAlerts: false)
            for name in [names.olderAudioVisual, names.olderSwitch] {
                #expect(night.contains(name), "\(names.language) lacks \(name): \(night)")
            }
        }
    }

    @Test("the flash sentence follows the Alerts footer with the gap the language puts between sentences")
    func joinsTheFooter() {
        Localization.$override.withValue(.french) {
            #expect(FlashAlertsHint.after("Fin.", flashForAlerts: true).hasPrefix("Fin. Pour que le flash"))
        }
        Localization.$override.withValue(.chineseSimplified) {
            #expect(FlashAlertsHint.after("结束。", flashForAlerts: true).hasPrefix("结束。若想让"))
        }
    }
}
