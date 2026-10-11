import Testing
@testable import OzenPlatform

@Suite("Apple speech recognition's locale")
struct AppleSpeechLocaleTests {
    @Test("each spoken language is asked for with the region Apple's recognizer needs, not as a bare code")
    func regions() {
        #expect(AppleSpeechEngine.localeIdentifier(for: "he") == "he-IL")
        #expect(AppleSpeechEngine.localeIdentifier(for: "en") == "en-US")
        #expect(AppleSpeechEngine.localeIdentifier(for: "ar") == "ar-SA")
        #expect(AppleSpeechEngine.localeIdentifier(for: "ru") == "ru-RU")
        #expect(AppleSpeechEngine.localeIdentifier(for: "zh") == "zh-CN")
    }

    @Test("a code that already names its region, or that no listed language has, is asked for as it is")
    func asItIs() {
        #expect(AppleSpeechEngine.localeIdentifier(for: "en-GB") == "en-GB")
        #expect(AppleSpeechEngine.localeIdentifier(for: "xx") == "xx")
    }
}
