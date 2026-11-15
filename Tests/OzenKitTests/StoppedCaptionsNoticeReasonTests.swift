import Foundation
import Testing
@testable import OzenKit

@Suite("StoppedCaptionsNotice names the cause")
struct StoppedCaptionsNoticeReasonTests {
    private let openOzen = tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")

    private func engineFailure(_ kind: EngineUnavailability.Kind, removed: Bool = false) -> PipelineFailure {
        PipelineFailure(
            kind: .engineUnavailable, detail: "",
            engineUnavailability: EngineUnavailability(kind: kind, detail: "", modelWasRemoved: removed)
        )
    }

    private func body(_ failure: PipelineFailure, engine: TranscriptionEngineKind? = nil, language: UILanguage = .hebrew) -> String {
        let context = StoppedCaptionsNotice.FailureContext(engine: engine, spokenLanguage: language)
        return StoppedCaptionsNotice.content(for: .failed(failure, context)).body
    }

    private func expectNamed(_ body: String, _ cause: String) {
        #expect(body.contains(cause))
        #expect(body.hasSuffix(openOzen))
    }

    @Test("a microphone that stopped responding says so, with its name when known")
    func audioSession() {
        let plain = PipelineFailure(kind: .audioSessionFailed, detail: "")
        expectNamed(body(plain), tr("המיקרופון לא מגיב", "The microphone isn’t responding"))
        let named = PipelineFailure(kind: .audioSessionFailed, detail: "", inputName: "AirPods")
        expectNamed(body(named), tr("המיקרופון \u{2068}%1\u{2069} לא מגיב", "%1 isn’t responding", args: ["AirPods"]))
    }

    @Test("transcription stopping says so")
    func transcriptionStopped() {
        let stopped = PipelineFailure(kind: .transcriptionStopped, detail: "")
        expectNamed(body(stopped), tr("התמלול נעצר", "Transcription stopped"))
    }

    @Test("a spoken language the engine lacks names the engine and the language chosen")
    func languageNotSupported() {
        let failure = engineFailure(.languageNotSupportedOnDevice)
        let expected = tr(
            "%1 לא זמין בשפה %2 במכשיר הזה", "%1 isn’t available in %2 on this device",
            args: [tr("זיהוי הדיבור של אפל", "Apple’s speech recognition"), UILanguage.french.spokenName]
        )
        expectNamed(body(failure, engine: .appleSpeech, language: .french), expected)
    }

    @Test("a failed download, a damaged model and a failed load are told apart")
    func models() {
        expectNamed(body(engineFailure(.modelDownloadFailed)), tr("הורדת המודל נכשלה", "Downloading the model failed"))
        expectNamed(
            body(engineFailure(.modelDownloadFailed, removed: true)),
            tr("מודל הדיבור בטלפון הזה נפגם, ולכן אוזן הסירה אותו ותוריד אותו שוב.", "The speech model on this phone was damaged, so Ozen removed it and will download it again.")
        )
        expectNamed(body(engineFailure(.modelLoadFailed)), tr("טעינת המודל נכשלה", "Loading the model failed"))
    }

    @Test("an engine that is down, or down for no known reason, is named")
    func unavailable() {
        let phone = tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
        expectNamed(body(engineFailure(.temporarilyUnavailable)), tr("%1 לא זמין כרגע", "%1 isn’t available right now", args: [phone]))
        expectNamed(body(engineFailure(.other)), tr("%1 לא זמין", "%1 isn’t available", args: [phone]))
    }
}
