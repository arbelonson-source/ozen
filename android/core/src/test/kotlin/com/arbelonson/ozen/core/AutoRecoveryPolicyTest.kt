package com.arbelonson.ozen.core

import com.arbelonson.ozen.core.AutoRecoveryPolicy.Schedule as S
import com.arbelonson.ozen.core.EngineUnavailability.Kind as E
import com.arbelonson.ozen.core.PipelineFailure.Kind as K
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun failure(kind: PipelineFailure.Kind, engine: EngineUnavailability.Kind? = null) =
    PipelineFailure(kind, "", engine?.let { EngineUnavailability(it, "") })

class AutoRecoveryPolicyTest {
    @Test
    fun `only failures that can clear up on their own are retried`() {
        assertEquals(S.Never, AutoRecoveryPolicy.schedule(failure(K.MicrophonePermissionDenied)))
        assertEquals(S.Never, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.PermissionDenied)))
        assertEquals(S.Never, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.LanguageNotSupportedOnDevice)))
        assertEquals(S.Glitch, AutoRecoveryPolicy.schedule(failure(K.TranscriptionStopped)))
        assertEquals(S.Glitch, AutoRecoveryPolicy.schedule(failure(K.AudioSessionFailed)))
        assertEquals(S.Glitch, AutoRecoveryPolicy.schedule(failure(K.NoAudioInputs)))
        assertEquals(S.Glitch, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.TemporarilyUnavailable)))
        assertEquals(S.Download, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.ModelDownloadFailed)))
        assertEquals(S.LoadFailure, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.ModelLoadFailed)))
        assertEquals(S.Never, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.CloudKeyNeeded)))
        assertEquals(S.Never, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.CloudOutOfCredit)))
        assertEquals(S.Glitch, AutoRecoveryPolicy.schedule(failure(K.EngineUnavailable, E.NoInternet)))
    }

    @Test
    fun `glitches back off through their delays and then stop`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0, 3.0, 8.0), downloadDelays = listOf(60.0))
        val stopped = failure(PipelineFailure.Kind.TranscriptionStopped)
        val delays = (0 until 4).map { policy.nextDelay(stopped) }
        assertEquals(listOf(1.0, 3.0, 8.0, null), delays)
        assertEquals(3, policy.attempts)
    }

    @Test
    fun `a download waits longer between tries than a glitch`() {
        val policy = AutoRecoveryPolicy()
        val first = policy.nextDelay(failure(PipelineFailure.Kind.EngineUnavailable, EngineUnavailability.Kind.ModelDownloadFailed))
        assertTrue((first ?: 0.0) >= 15)
    }

    @Test
    fun `a model that won't load gets two tries, not the full glitch schedule`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0, 3.0, 8.0, 20.0), downloadDelays = emptyList())
        val broken = failure(PipelineFailure.Kind.EngineUnavailable, EngineUnavailability.Kind.ModelLoadFailed)
        val delays = (0 until 3).map { policy.nextDelay(broken) }
        assertEquals(listOf(1.0, 3.0, null), delays)
    }

    @Test
    fun `a new kind of trouble gets its own fresh tries, not runoff from an unrelated one`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0, 3.0, 8.0, 20.0), downloadDelays = listOf(15.0))
        val glitch = failure(PipelineFailure.Kind.TranscriptionStopped)
        val loadFailure = failure(PipelineFailure.Kind.EngineUnavailable, EngineUnavailability.Kind.ModelLoadFailed)

        (0 until 4).map { policy.nextDelay(glitch) }
        assertNull(policy.nextDelay(glitch))

        assertEquals(1.0, policy.nextDelay(loadFailure))
        assertEquals(3.0, policy.nextDelay(loadFailure))
        assertNull(policy.nextDelay(loadFailure))
    }

    @Test
    fun `a problem that keeps switching kind still stops retrying eventually`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0, 3.0, 8.0, 20.0), downloadDelays = listOf(15.0))
        val download = failure(PipelineFailure.Kind.EngineUnavailable, EngineUnavailability.Kind.ModelDownloadFailed)
        val loadFailure = failure(PipelineFailure.Kind.EngineUnavailable, EngineUnavailability.Kind.ModelLoadFailed)

        var sawNull = false
        var retries = 0
        for (i in 0 until policy.maxAttemptsAcrossSchedules * 2) {
            val delay = policy.nextDelay(if (i % 2 == 0) download else loadFailure)
            if (delay == null) {
                sawNull = true
                break
            }
            retries += 1
        }
        assertTrue(sawNull)
        assertEquals(policy.maxAttemptsAcrossSchedules, retries)
        assertTrue(policy.overallAttempts <= policy.maxAttemptsAcrossSchedules)

        policy.reset()
        retries = 0
        for (i in 0 until policy.maxAttemptsAcrossSchedules * 2) {
            if (policy.nextDelay(if (i % 2 == 0) download else loadFailure) == null) break
            retries += 1
        }
        assertEquals(policy.maxAttemptsAcrossSchedules, retries)
    }

    @Test
    fun `reset starts the count over, and the disabled policy never retries`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0), downloadDelays = emptyList())
        val stopped = failure(PipelineFailure.Kind.TranscriptionStopped)
        policy.nextDelay(stopped)
        assertNull(policy.nextDelay(stopped))
        policy.reset()
        assertEquals(1.0, policy.nextDelay(stopped))

        val disabled = AutoRecoveryPolicy.disabled()
        assertNull(disabled.nextDelay(stopped))
    }

    @Test
    fun `a copy counts on its own`() {
        val policy = AutoRecoveryPolicy(glitchDelays = listOf(1.0, 2.0), downloadDelays = emptyList())
        val stopped = failure(PipelineFailure.Kind.TranscriptionStopped)
        val copy = policy.copy()
        assertEquals(1.0, policy.nextDelay(stopped))
        assertEquals(0, copy.attempts)
        assertEquals(1.0, copy.nextDelay(stopped))
    }
}
