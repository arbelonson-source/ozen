package com.arbelonson.ozen.whisper

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelFileLoaderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(File(context.filesDir, "device-test"), "model.bin")

    private fun megabytes() = Debug.getNativeHeapAllocatedSize() / 1_000_000

    @Test
    fun aModelWhoseLoadingIsStoppedDoesNotStayInMemory() = runBlocking {
        assumeTrue("put model.bin in ${file.parent}", file.exists())
        val before = megabytes()
        val inMemory = CompletableDeferred<Unit>()
        val stopped = CountDownLatch(1)
        val loader = ModelFileLoader(file, context.applicationInfo.nativeLibraryDir) { path, libraries ->
            WhisperModel.load(path, libraries).also {
                inMemory.complete(Unit)
                stopped.await(60, TimeUnit.SECONDS)
            }
        }
        val loading = launch(Dispatchers.Default) { loader.load("he", cellularDownloadAllowed = false) {} }
        withTimeout(60_000) { inMemory.await() }
        val loaded = megabytes() - before
        loading.cancel()
        stopped.countDown()
        withTimeout(60_000) { loading.join() }
        val left = megabytes() - before
        Log.i("OzenWhisper", "a load stopped while the model was being read: $loaded MB loaded, $left MB left")
        assertTrue("the model was not in memory to begin with ($loaded MB)", loaded > 400)
        assertTrue("a load stopped while the model was being read left $left MB allocated", left < 100)
    }
}
