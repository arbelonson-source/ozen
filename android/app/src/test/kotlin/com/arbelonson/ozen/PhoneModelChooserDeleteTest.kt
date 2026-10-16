package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelDeletion
import com.arbelonson.ozen.core.ReleaseFileFetching
import com.arbelonson.ozen.core.SettingsStore
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PhoneModelChooserDeleteTest {
    private val folder = Files.createTempDirectory("ozen-chooser-delete").toFile()
    private val models = File(folder, "models").also { it.mkdirs() }
    private val settings = SettingsHolder(SettingsStore(File(folder, "ozen-settings.json")))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val target = "ggml-small-q5_1.bin"
    private val keep = "ggml-base-q5_1.bin"

    private val fetcher = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = Unit

        override fun sha256(file: File): String = ""
    }

    private fun chooser(): PhoneModelChooser {
        File(models, target).writeBytes(ByteArray(4))
        File(models, keep).writeBytes(ByteArray(4))
        settings.change { it.phoneModelFile = keep }
        return PhoneModelChooser(
            settings = settings,
            modelFolder = models,
            fetcher = { fetcher },
            network = { null },
            freeSpace = { 100_000_000_000 },
            scope = scope,
            measure = { null },
            applied = {},
            remove = { file ->
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                file.delete()
            },
        )
    }

    @AfterTest
    fun cleanUp() {
        release.countDown()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        folder.deleteRecursively()
    }

    @Test
    fun `choosing a model while its deletion is under way never leaves it chosen but gone`() = runBlocking {
        assertTrue(PhoneModelCatalog.byFile(target) != null)
        val chooser = chooser()
        val deleting = scope.launch { chooser.delete(target) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val choosing = Thread { chooser.choose(target) }.also { it.start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (choosing.state != Thread.State.BLOCKED && choosing.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
        release.countDown()
        choosing.join(10_000)
        deleting.join()
        val chosen = settings.current.value.phoneModelFile
        assertFalse(chosen == target && !File(models, target).isFile, "chosen=$chosen but the file is gone")
    }

    @Test
    fun `a delete whose caller is cancelled mid-removal still refreshes the installed list`() = runBlocking {
        val chooser = chooser()
        assertTrue(target in chooser.installed.value)
        var result: PhoneModelDeletion.Result? = null
        val deleting = scope.launch { result = chooser.delete(target) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val cancelling = scope.launch { deleting.cancelAndJoin() }
        Thread.sleep(50)
        release.countDown()
        cancelling.join()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (target in chooser.installed.value && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(File(models, target).exists())
        assertEquals(setOf(keep), chooser.installed.value)
    }
}
