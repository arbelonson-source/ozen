import Foundation

extension StoppedCaptionsNotice {
    static var openOzenToContinue: String { tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.") }

    static func namedProblem(_ failure: PipelineFailure, _ context: FailureContext) -> String? {
        let problem: String
        switch failure.kind {
        case .audioSessionFailed:
            problem = CaptionProblemText.microphoneNotResponding(inputName: failure.inputName)
        case .transcriptionStopped:
            problem = CaptionProblemText.transcriptionStopped
        case .engineUnavailable:
            guard let named = engineProblem(failure.engineUnavailability, context) else { return nil }
            problem = named
        default:
            return nil
        }
        return problem + " " + openOzenToContinue
    }

    private static func engineProblem(_ unavailability: EngineUnavailability?, _ context: FailureContext) -> String? {
        switch unavailability?.kind {
        case .languageNotSupportedOnDevice?:
            CaptionProblemText.languageNotAvailable(engine: context.engine, language: context.spokenLanguage)
        case .modelDownloadFailed?:
            CaptionProblemText.modelDownloadFailed(modelWasRemoved: unavailability?.modelWasRemoved == true)
        case .modelLoadFailed?:
            CaptionProblemText.modelLoadFailed
        case .temporarilyUnavailable?:
            CaptionProblemText.temporarilyUnavailable(engine: context.engine)
        case .other?, nil:
            CaptionProblemText.unavailable(engine: context.engine)
        default:
            nil
        }
    }
}
