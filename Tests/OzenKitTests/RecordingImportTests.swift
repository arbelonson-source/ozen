import Testing
@testable import OzenKit

@Suite("RecordingImport")
struct RecordingImportTests {
    @Test("the person's name is the file name without its number or extension")
    func nameFromFileName() {
        #expect(RecordingImport.personName(fromFileName: "Savta 1.m4a") == "Savta")
        #expect(RecordingImport.personName(fromFileName: "Savta 2.m4a") == "Savta")
        #expect(RecordingImport.personName(fromFileName: "סבתא 3.m4a") == "סבתא")
        #expect(RecordingImport.personName(fromFileName: "סבא יוסי 1.wav") == "סבא יוסי")
        #expect(RecordingImport.personName(fromFileName: "Aba_2.mp3") == "Aba")
        #expect(RecordingImport.personName(fromFileName: "Ima-12.aac") == "Ima")
        #expect(RecordingImport.personName(fromFileName: "Dana (2).m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "Dana.m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "Noa2.m4a") == "Noa")
        #expect(RecordingImport.personName(fromFileName: "Bat_Sheva 1.m4a") == "Bat Sheva")
    }

    @Test("invisible direction marks, as text pasted from a Hebrew message carries them, don't make another person")
    func directionMarks() {
        #expect(RecordingImport.personName(fromFileName: "סבתא 1\u{200F}.m4a") == "סבתא")
        #expect(RecordingImport.personName(fromFileName: "\u{200F}סבתא 2.m4a") == "סבתא")
        #expect(RecordingImport.personName(fromFileName: "\u{2067}סבא יוסי\u{2069} 3.m4a") == "סבא יוסי")
        #expect(RecordingImport.personName(fromFileName: "Dana\u{200E} (2).m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "\u{200F}4\u{200F}.m4a") == nil)
    }

    @Test("a date or several numbers after the name all come off")
    func datedNames() {
        #expect(RecordingImport.personName(fromFileName: "Dana 2024-05-03.m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "Dana 03.05.2024.m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "Dana_2024_05.m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "דנה 05-03.m4a") == "דנה")
        #expect(RecordingImport.personName(fromFileName: "Dana 2024-05-03 (2).m4a") == "Dana")
        #expect(RecordingImport.personName(fromFileName: "Dana 1234567890123456789012345678901234x.m4a") == "Dana 1234567890123456789012345678901234x")
    }

    @Test("a file named only by a number gives no name")
    func numberOnly() {
        #expect(RecordingImport.personName(fromFileName: "12.m4a") == nil)
        #expect(RecordingImport.personName(fromFileName: " .m4a") == nil)
    }

    @Test("the summary names who was added, how many recordings each, and what was left out")
    func summary() {
        let result = RecordingImport.Result(added: ["Savta": 2, "Aba": 1], unusable: ["12.m4a"])
        let english = Localization.$override.withValue(.english) { result.summary }
        #expect(english == "Added: Aba, Savta (2 recordings)\n\nNot added (too little speech, or no name in the file name): 12.m4a")
        let onlyAdded = Localization.$override.withValue(.english) { RecordingImport.Result(added: ["Aba": 1]).summary }
        #expect(onlyAdded == "Added: Aba")
    }
}
