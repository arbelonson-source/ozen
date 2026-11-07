package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CaptionPipelineNetworkNoticeTest {
    @Test
    fun `every change of network is announced to the screen while captions listen`() = runTest {
        val monitor = FakeNetworkMonitor(NetworkConditions.wifi)
        val pipeline = captionPipeline(
            audio = FakeAudioCapturer(),
            engineFactory = { FakeEngine() },
            embedder = FakeEmbedder(),
            network = monitor,
        )
        pipeline.start(AppSettings.default)
        val seen = mutableListOf<NetworkConditions?>()
        pipeline.onNetworkChanged = { seen += pipeline.networkConditions }
        monitor.change(NetworkConditions.cellular)
        monitor.change(NetworkConditions.wifi)
        assertEquals(listOf<NetworkConditions?>(NetworkConditions.cellular, NetworkConditions.wifi), seen)
    }
}
