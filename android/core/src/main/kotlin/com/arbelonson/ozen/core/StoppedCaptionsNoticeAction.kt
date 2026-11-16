package com.arbelonson.ozen.core

enum class NoticeAction {
    OpenToContinue,
    AllowMicrophone,
    FreeSpace,
    CheckMicrophone,
    CheckConnection,
    FixCloudService,
    CheckComputer,
    PairComputer,
    FixComputerSettings,
    TurnSoundAlertsOn,
    NothingToDo,
}

fun StoppedCaptionsNotice.Companion.action(cause: StoppedCaptionsNotice.Cause): NoticeAction = when (cause) {
    StoppedCaptionsNotice.Cause.CallEnded,
    StoppedCaptionsNotice.Cause.EndedByPhone,
    StoppedCaptionsNotice.Cause.OtherAppHasMicrophone,
    -> NoticeAction.OpenToContinue
    StoppedCaptionsNotice.Cause.SoundAlertsNotWorking -> NoticeAction.NothingToDo
    StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent -> NoticeAction.CheckMicrophone
    StoppedCaptionsNotice.Cause.SoundAlertsStopped -> NoticeAction.TurnSoundAlertsOn
    is StoppedCaptionsNotice.Cause.Failed -> failureAction(cause.failure)
}

private fun failureAction(failure: PipelineFailure): NoticeAction {
    val unavailability = failure.engineUnavailability
    return when {
        failure.kind == PipelineFailure.Kind.MicrophonePermissionDenied ||
            unavailability?.kind == EngineUnavailability.Kind.PermissionDenied -> NoticeAction.AllowMicrophone
        unavailability?.kind == EngineUnavailability.Kind.NotEnoughStorage -> NoticeAction.FreeSpace
        failure.kind == PipelineFailure.Kind.NoAudioInputs -> NoticeAction.CheckMicrophone
        unavailability?.kind == EngineUnavailability.Kind.CloudKeyNeeded ||
            unavailability?.kind == EngineUnavailability.Kind.CloudLanguageNotCovered ||
            unavailability?.kind == EngineUnavailability.Kind.CloudOutOfCredit -> NoticeAction.FixCloudService
        unavailability?.kind == EngineUnavailability.Kind.NoInternet -> NoticeAction.CheckConnection
        HomeServer.refusedPlainAddress(unavailability) ||
            HomeServer.languageNotCovered(unavailability) ||
            HomeServer.answeredWrongly(unavailability) -> NoticeAction.FixComputerSettings
        HomeServer.missingAddress(unavailability) -> NoticeAction.PairComputer
        unavailability?.kind == EngineUnavailability.Kind.HomeServerUnreachable -> NoticeAction.CheckComputer
        unavailability?.kind == EngineUnavailability.Kind.HomeServerRejected -> NoticeAction.PairComputer
        else -> NoticeAction.OpenToContinue
    }
}
