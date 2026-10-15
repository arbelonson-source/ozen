package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CloudLanguageCoverTest {
    private val providers = listOf(CloudProvider.Deepgram, CloudProvider.AssemblyAI, CloudProvider.Soniox, CloudProvider.Speechmatics)

    private fun engineFor(provider: CloudProvider, http: FakeCloudHTTP) =
        provider.engine(http = http, connector = StreamDialer(null), apiKey = { "test-key" })

    @Test
    fun `a service that does not know the language says so before sending anything`() = runTest {
        for (provider in providers) {
            assertTrue(!provider.covers("am"), "$provider")
            val http = FakeCloudHTTP()
            val result = engineFor(provider, http).prepare("am") { }
            val why = assertIs<EngineAvailability.Unavailable>(result, "$provider").why
            assertEquals(EngineUnavailability.Kind.CloudLanguageNotCovered, why.kind, "$provider")
            assertEquals(0, http.requests.size, "$provider")
        }
    }

    @Test
    fun `a language the service knows still reaches the key check`() = runTest {
        for (provider in providers) {
            assertTrue(provider.covers("he"), "$provider")
            val http = FakeCloudHTTP()
            engineFor(provider, http).prepare("he") { }
            assertEquals(1, http.requests.size, "$provider")
        }
    }

    @Test
    fun `the language check comes before the key check`() = runTest {
        val http = FakeCloudHTTP()
        val engine = CloudProvider.Deepgram.engine(http = http, connector = StreamDialer(null), apiKey = { null })
        val why = assertIs<EngineAvailability.Unavailable>(engine.prepare("am") { }).why
        assertEquals(EngineUnavailability.Kind.CloudLanguageNotCovered, why.kind)
    }
}
