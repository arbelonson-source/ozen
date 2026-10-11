package com.arbelonson.ozen

import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.YamnetSounds
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

class YamnetAssetsTest {
    private val assets = File("src/main/assets")

    @Test
    fun `the label list beside the model is the one stored inside it`() {
        val inside = ZipFile(File(assets, YamnetSoundDetector.MODEL)).use { zip ->
            zip.getInputStream(zip.getEntry("yamnet_label_list.txt")).bufferedReader().readLines()
        }
        val beside = File(assets, YamnetSoundDetector.LABELS).readLines()
        assertEquals(521, inside.size)
        assertEquals(inside, beside)
    }

    @Test
    fun `every sound Ozen alerts for is one the bundled model knows`() {
        val labels = File(assets, YamnetSoundDetector.LABELS).readLines()
        val indices = YamnetSounds.indices(labels)
        assertEquals(SoundEventCatalog.identifiers, indices.keys)
        for ((identifier, wanted) in YamnetSounds.labels) {
            assertEquals(wanted.size, indices.getValue(identifier).size, identifier)
        }
    }
}
