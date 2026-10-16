package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
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

    @Test
    fun `a regional tag or the old Hebrew code is the same language as its plain code`() = runTest {
        for (provider in providers) {
            for (code in listOf("en-GB", "EN-us", "iw", "he-IL", "pt-BR")) {
                assertTrue(provider.covers(code), "$provider $code")
                val http = FakeCloudHTTP()
                engineFor(provider, http).prepare(code) { }
                assertEquals(1, http.requests.size, "$provider $code")
            }
            assertTrue(!provider.covers("am-ET") && !provider.covers("xx-YY"), "$provider")
        }
    }

    @Test
    fun `the request carries the plain code for the old Hebrew code`() = runTest {
        val http = FakeCloudHTTP(listOf(FakeCloudHTTP.Answer.Text("שלום")), emptyList(), testScheduler.timeSource)
        val engine = CloudSpeechEngine(provider = CloudProvider.Deepgram, http = http, timeSource = testScheduler.timeSource, apiKey = { "k" })
        val feed = AudioFeed()
        feed.add(List(16) { FloatArray(1_024) { index -> 0.05f * kotlin.math.sin(index.toFloat() * 0.3f) } } + List(16) { FloatArray(1_024) })
        feed.finish()
        runCatching { engine.stream("iw", feed.flow).toList() }
        val request = assertNotNull(http.transcriptionRequests.firstOrNull())
        assertTrue("language=he" in request.url.toString(), request.url.toString())
    }
}
