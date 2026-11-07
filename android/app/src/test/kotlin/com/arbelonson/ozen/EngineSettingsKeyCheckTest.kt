package com.arbelonson.ozen

import com.arbelonson.ozen.core.CloudHTTP
import com.arbelonson.ozen.core.CloudHTTPRequest
import com.arbelonson.ozen.core.CloudHTTPResponse
import com.arbelonson.ozen.core.CloudKeyCheck
import com.arbelonson.ozen.core.CloudProvider
import com.arbelonson.ozen.core.HomeServerCheck
import com.arbelonson.ozen.core.SettingsStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class EngineSettingsKeyCheckTest {
    private class HeldHTTP : CloudHTTP {
        class Call(val request: CloudHTTPRequest, val reply: CompletableDeferred<CloudHTTPResponse>)

        val calls = ArrayList<Call>()

        override suspend fun send(request: CloudHTTPRequest): CloudHTTPResponse {
            val call = Call(request, CompletableDeferred())
            calls.add(call)
            return call.reply.await()
        }

        fun answer(index: Int, status: Int) = calls[index].reply.complete(CloudHTTPResponse(status, "{}".toByteArray()))
    }

    private val folder = Files.createTempDirectory("ozen-key-check").toFile()
    private val settings = SettingsHolder(SettingsStore(File(folder, "ozen-settings.json")))
    private val keys = CloudKeyStore(File(folder, "cloud-keys"), ReversingCipher())
    private val codes = HomeServerCodeStore(File(folder, "home-server-code"), ReversingCipher())
    private val http = HeldHTTP()
    private val engineSettings = EngineSettings(
        settings,
        keys,
        codes,
        checkHomeComputer = { HomeServerCheck.Unreachable },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        http = http,
    ) { }

    @AfterTest
    fun removeFolder() {
        folder.deleteRecursively()
    }

    private suspend fun save(key: String, provider: CloudProvider = CloudProvider.Deepgram): Boolean {
        engineSettings.chooseCloudService(provider)
        engineSettings.acknowledgeCloud(provider)
        return engineSettings.saveCloudKey(key)
    }

    @Test
    fun `saving a key tries it at its service and publishes what came back`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        assertNull(engineSettings.cloudKeyCheck.value)
        assertEquals(1, http.calls.size)
        assertEquals(CloudProvider.Deepgram.keyCheckRequest("dg-key"), http.calls[0].request)
        http.answer(0, 401)
        assertEquals(CloudKeyCheck.Rejected, engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `an unreachable service leaves the key saved`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        http.calls[0].reply.completeExceptionally(IOException("offline"))
        assertEquals(CloudKeyCheck.Unreachable, engineSettings.cloudKeyCheck.value)
        assertEquals("dg-key", keys.read(CloudProvider.Deepgram))
    }

    @Test
    fun `a reply that comes after the key was deleted is dropped`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        engineSettings.deleteCloudKey()
        http.answer(0, 200)
        assertNull(engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `a shown result goes away when the key is deleted`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        http.answer(0, 200)
        assertEquals(CloudKeyCheck.Accepted, engineSettings.cloudKeyCheck.value)
        engineSettings.deleteCloudKey()
        assertNull(engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `a reply that comes after another service was chosen is dropped`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        engineSettings.chooseCloudService(CloudProvider.Groq)
        http.answer(0, 200)
        assertNull(engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `a shown result goes away when another service is chosen`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        http.answer(0, 200)
        engineSettings.chooseCloudService(CloudProvider.Groq)
        assertNull(engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `a second save before the first reply shows only the second result`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        assertTrue(save("dg-key"))
        http.answer(1, 401)
        assertEquals(CloudKeyCheck.Rejected, engineSettings.cloudKeyCheck.value)
        http.answer(0, 200)
        assertEquals(CloudKeyCheck.Rejected, engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `an older reply arriving first is not shown for a newer key`() = runBlocking<Unit> {
        assertTrue(save("dg-old"))
        assertTrue(save("dg-new"))
        http.answer(0, 200)
        assertNull(engineSettings.cloudKeyCheck.value)
        http.answer(1, 403)
        assertEquals(CloudKeyCheck.Rejected, engineSettings.cloudKeyCheck.value)
    }

    @Test
    fun `a reply is dropped when the saved key is no longer the one that was tried`() = runBlocking<Unit> {
        assertTrue(save("dg-key"))
        keys.save("dg-replaced-elsewhere", CloudProvider.Deepgram)
        http.answer(0, 200)
        assertNull(engineSettings.cloudKeyCheck.value)
    }
}
