package com.arbelonson.ozen.core

object PartialFileRun {
    fun result(
        name: String,
        language: UILanguage,
        model: PhoneModel,
        lines: List<TranscribedLine>,
        problem: FileTranscriptionProblem,
        save: (List<TranscribedLine>) -> HistorySave,
    ): FileTranscription =
        if (lines.isEmpty()) FileTranscription.Failed(name, problem)
        else FileTranscription.Done(name, language, model, lines, save(lines), endMissing = true)
}
