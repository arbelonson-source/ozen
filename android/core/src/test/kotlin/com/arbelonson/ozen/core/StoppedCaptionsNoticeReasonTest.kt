package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoppedCaptionsNoticeReasonTest {
    private val openOzen = tr("פתחו את אוזן כדי להמשיך.", "Open Ozen to continue.")

    private fun engineFailure(kind: EngineUnavailability.Kind) = PipelineFailure(
        PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(kind, ""),
    )

    private fun body(
        failure: PipelineFailure,
        engine: TranscriptionEngineKind? = null,
        language: UILanguage = UILanguage.Hebrew,
    ) = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.Failed(failure, engine, language)).body

    private fun assertNamed(cause: String, body: String) {
        assertTrue(body.contains(cause), "<$body> should name <$cause>")
        assertTrue(body.endsWith(openOzen), "<$body> should end by saying what to do")
    }

    @Test
    fun `a microphone that stopped responding says so, with its name when known`() {
        val plain = PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "")
        assertNamed(tr("המיקרופון לא מגיב", "The microphone isn’t responding"), body(plain))
        val named = PipelineFailure(PipelineFailure.Kind.AudioSessionFailed, "", inputName = "Headset")
        assertNamed(tr("המיקרופון ⁨%1⁩ לא מגיב", "%1 isn’t responding", listOf("Headset")), body(named))
    }

    @Test
    fun `transcription stopping says so`() {
        val stopped = PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "")
        assertNamed(tr("התמלול נעצר", "Transcription stopped"), body(stopped))
    }

    @Test
    fun `a spoken language the engine lacks names the engine and the language chosen`() {
        val expected = tr(
            "%1 לא זמין בשפה %2 במכשיר הזה", "%1 isn’t available in %2 on this device",
            listOf(tr("זיהוי הדיבור המובנה בטלפון", "The phone’s built-in recognition"), UILanguage.French.spokenName),
        )
        val failure = engineFailure(EngineUnavailability.Kind.LanguageNotSupportedOnDevice)
        assertNamed(expected, body(failure, TranscriptionEngineKind.AppleSpeech, UILanguage.French))
    }

    @Test
    fun `a model the phone has too little memory for is named as that, not as a failed load`() {
        assertNamed(
            tr("אין בטלפון הזה מספיק זיכרון למודל הזה", "This phone doesn’t have enough memory for this model"),
            body(engineFailure(EngineUnavailability.Kind.NotEnoughMemory)),
        )
    }

    @Test
    fun `a failed download, a damaged model, a missing model and a failed load are told apart`() {
        assertNamed(tr("הורדת המודל נכשלה", "Downloading the model failed"), body(engineFailure(EngineUnavailability.Kind.ModelDownloadFailed)))
        assertNamed(DamagedModelNotice.text(), body(engineFailure(EngineUnavailability.Kind.ModelDamaged)))
        assertNamed(tr("טעינת המודל נכשלה", "Loading the model failed"), body(engineFailure(EngineUnavailability.Kind.ModelLoadFailed)))
        assertNamed(
            tr("מודל הדיבור עוד לא הורד לטלפון", "The language model isn’t on this phone yet"),
            body(engineFailure(EngineUnavailability.Kind.ModelNotOnDevice)),
        )
    }

    @Test
    fun `an engine that is down, or down for no known reason, is named`() {
        val phone = tr("זיהוי הדיבור בטלפון", "The phone’s speech recognition")
        assertNamed(
            tr("%1 לא זמין כרגע", "%1 isn’t available right now", listOf(phone)),
            body(engineFailure(EngineUnavailability.Kind.TemporarilyUnavailable)),
        )
        assertNamed(tr("%1 לא זמין", "%1 isn’t available", listOf(phone)), body(engineFailure(EngineUnavailability.Kind.Other)))
    }

    @Test
    fun `when the phone refuses to let Ozen listen the notice says what to tap`() {
        val content = StoppedCaptionsNotice.content(StoppedCaptionsNotice.Cause.EndedByPhone)
        assertEquals(
            tr("הטלפון לא איפשר לאוזן להאזין. לחצו על התחלת כתוביות כדי לנסות שוב.", "The phone wouldn’t let Ozen listen. Tap Start captions to try again."),
            content.body,
        )
    }

    @Test
    fun `the cause and what to do are two sentences, a title getting its full stop and a sentence not getting a second one`() {
        val stopped = PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "")
        val damaged = engineFailure(EngineUnavailability.Kind.ModelDamaged)
        Localization.withLanguage(UILanguage.Hebrew) {
            assertEquals("התמלול נעצר. פתחו את אוזן כדי להמשיך.", body(stopped))
            assertEquals("מודל הדיבור בטלפון הזה נפגם, ולכן אוזן הסירה אותו ותוריד אותו שוב. פתחו את אוזן כדי להמשיך.", body(damaged))
        }
        Localization.withLanguage(UILanguage.English) {
            assertEquals("Transcription stopped. Open Ozen to continue.", body(stopped))
            assertEquals("The speech model on this phone was damaged, so Ozen removed it and will download it again. Open Ozen to continue.", body(damaged))
        }
    }
}
