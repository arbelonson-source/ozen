import Foundation

extension StoppedCaptionsNotice {
    static var openOzenToContinue: String { tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.") }

    static func namedProblem(_ failure: PipelineFailure, _ context: FailureContext) -> String? {
        let problem: Problem
        switch failure.kind {
        case .audioSessionFailed:
            problem = .title(CaptionProblemText.microphoneNotResponding(inputName: failure.inputName))
        case .transcriptionStopped:
            problem = .title(CaptionProblemText.transcriptionStopped)
        case .engineUnavailable:
            guard let named = engineProblem(failure.engineUnavailability, context) else { return nil }
            problem = named
        default:
            return nil
        }
        switch problem {
        case .title(let title): return tr("%1. פתחו את אוזן כדי להמשיך.", "%1. Open Ozen to continue.", args: ["\(title)"])
        case .sentence(let sentence): return sentence + " " + openOzenToContinue
        }
    }

    private enum Problem {
        case title(String)
        case sentence(String)
    }

    private static func engineProblem(_ unavailability: EngineUnavailability?, _ context: FailureContext) -> Problem? {
        switch unavailability?.kind {
        case .languageNotSupportedOnDevice?:
            .title(CaptionProblemText.languageNotAvailable(engine: context.engine, language: context.spokenLanguage))
        case .modelDownloadFailed? where unavailability?.modelWasRemoved == true:
            .sentence(CaptionProblemText.damagedModelRemoved)
        case .modelDownloadFailed?:
            .title(CaptionProblemText.modelDownloadFailed(modelWasRemoved: false))
        case .modelLoadFailed?:
            .title(CaptionProblemText.modelLoadFailed)
        case .temporarilyUnavailable?:
            .title(CaptionProblemText.temporarilyUnavailable(engine: context.engine))
        case .other?, nil:
            .title(CaptionProblemText.unavailable(engine: context.engine))
        default:
            nil
        }
    }
}
