package com.arbelonson.ozen.core

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class CloudKeyCheckTest {
    private fun check(provider: CloudProvider, answer: FakeCloudHTTP.Answer): CloudKeyCheck =
        runBlocking { CloudKeyCheck.result(provider, "fake-key", FakeCloudHTTP(keyChecks = listOf(answer))) }

    private fun status(code: Int, body: String = "{}") = FakeCloudHTTP.Answer.Status(code, body)

    @Test
    fun `a key the service accepts is accepted`() {
        assertEquals(CloudKeyCheck.Accepted, check(CloudProvider.Deepgram, status(200)))
        assertEquals(CloudKeyCheck.Accepted, check(CloudProvider.OpenRouter, status(200, """{"data":{"limit":3,"limit_remaining":2.5}}""")))
    }

    @Test
    fun `an accepted key on an account with nothing left is no credit`() {
        assertEquals(CloudKeyCheck.NoCredit, check(CloudProvider.OpenRouter, status(200, """{"data":{"limit":3,"limit_remaining":0}}""")))
    }

    @Test
    fun `401 and 403 mean the key is rejected`() {
        assertEquals(CloudKeyCheck.Rejected, check(CloudProvider.Deepgram, status(401)))
        assertEquals(CloudKeyCheck.Rejected, check(CloudProvider.Deepgram, status(403)))
    }

    @Test
    fun `an out of credit answer is no credit`() {
        assertEquals(CloudKeyCheck.NoCredit, check(CloudProvider.Deepgram, status(402)))
    }

    @Test
    fun `a busy or broken service is unreachable`() {
        assertEquals(CloudKeyCheck.Unreachable, check(CloudProvider.Deepgram, status(429)))
        assertEquals(CloudKeyCheck.Unreachable, check(CloudProvider.Deepgram, status(500)))
        assertEquals(CloudKeyCheck.Unreachable, check(CloudProvider.Deepgram, status(503)))
    }

    @Test
    fun `no connection is unreachable`() {
        assertEquals(CloudKeyCheck.Unreachable, check(CloudProvider.Deepgram, FakeCloudHTTP.Answer.Offline))
        val broken = CloudHTTP { throw IOException("reset") }
        assertEquals(CloudKeyCheck.Unreachable, runBlocking { CloudKeyCheck.result(CloudProvider.Soniox, "k", broken) })
    }

    @Test
    fun `cancelling the check is not hidden as unreachable`() {
        val cancelled = CloudHTTP { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { runBlocking { CloudKeyCheck.result(CloudProvider.Deepgram, "k", cancelled) } }
    }

    @Test
    fun `the request carries the key to the chosen service`() {
        val http = FakeCloudHTTP(keyChecks = listOf(status(200)))
        runBlocking { CloudKeyCheck.result(CloudProvider.Deepgram, "fake-key", http) }
        assertEquals(listOf(CloudProvider.Deepgram.keyCheckRequest("fake-key")), http.requests)
    }
}
