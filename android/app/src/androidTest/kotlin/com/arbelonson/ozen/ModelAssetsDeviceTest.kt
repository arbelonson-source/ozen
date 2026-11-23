package com.arbelonson.ozen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
class ModelAssetsDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun eachModelReadInPlaceStillHoldsAllItsBytesOnceItsFileIsClosed() {
        for (model in listOf(CamPlusPlusEmbedder.MODEL, YamnetSoundDetector.MODEL)) {
            val inPlace = ModelAssets.mapped(assets, model)
            val copy = ModelAssets.copied(assets, model)
            assertEquals(model, assets.openFd(model).use { it.length }, inPlace.capacity().toLong())
            assertEquals(model, copy, inPlace)
        }
    }

    @Test
    fun aFileStoredCompressedIsReadByCopyingItInstead() {
        assertThrows(FileNotFoundException::class.java) { assets.openFd(YamnetSoundDetector.LABELS) }
        val labels = ModelAssets.read(assets, YamnetSoundDetector.LABELS)
        assertEquals(ModelAssets.copied(assets, YamnetSoundDetector.LABELS), labels)
        assertEquals(assets.open(YamnetSoundDetector.LABELS).use { it.readBytes().size }, labels.capacity())
    }
}
