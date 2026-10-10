package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupModelTest {
    private fun status(
        engine: TranscriptionEngineKind = TranscriptionEngineKind.HomeServer,
        installed: Boolean = false,
        downloading: Double? = null,
        failed: Boolean = false,
        shortfall: Int? = null,
        network: NetworkConditions? = NetworkConditions.wifi,
        allowCellular: Boolean = false,
    ) = BackupModel.status(
        engine = engine, installed = installed, sizeMegabytes = 626, downloading = downloading, failed = failed,
        shortfallMegabytes = shortfall, network = network, allowCellular = allowCellular,
    )

    @Test
    fun `only a computer or cloud engine needs a backup - one already on the phone is ready`() {
        assertEquals(BackupModelStatus.NotNeeded, status(engine = TranscriptionEngineKind.WhisperKit))
        assertEquals(BackupModelStatus.NotNeeded, status(engine = TranscriptionEngineKind.AppleSpeech))
        assertEquals(BackupModelStatus.Missing(626), status(engine = TranscriptionEngineKind.Cloud))
        assertEquals(BackupModelStatus.Ready, status(installed = true))
        assertEquals(BackupModelStatus.Ready, status(installed = true, network = NetworkConditions.offline))
    }

    @Test
    fun `a download in progress shows its progress, before any network or room problem`() {
        assertEquals(BackupModelStatus.Downloading(0.4), status(downloading = 0.4, network = NetworkConditions.cellular))
        assertEquals(BackupModelStatus.Downloading(0.4), status(downloading = 0.4, shortfall = 100))
    }

    @Test
    fun `a missing backup waits for Wi-Fi like any model download, says when the phone is full, and can be retried after a failure`() {
        assertEquals(BackupModelStatus.WaitingForWiFi, status(network = NetworkConditions.cellular))
        assertEquals(BackupModelStatus.Missing(626), status(network = NetworkConditions.cellular, allowCellular = true))
        assertEquals(BackupModelStatus.Offline, status(network = NetworkConditions.offline))
        assertEquals(BackupModelStatus.NotEnoughRoom(300), status(shortfall = 300))
        assertEquals(BackupModelStatus.Failed, status(failed = true))
        assertTrue(BackupModel.canStart(status()))
        assertTrue(BackupModel.canStart(status(failed = true)))
        assertFalse(BackupModel.canStart(status(network = NetworkConditions.cellular)))
        assertFalse(BackupModel.canStart(status(downloading = 0.1)))
        assertFalse(BackupModel.canStart(status(installed = true)))
    }

    @Test
    fun `a computer that can't be reached offers the backup only while one could still be fetched`() {
        val unreachable = EngineUnavailability.Kind.HomeServerUnreachable
        assertTrue(BackupModel.shouldOffer(unreachable, BackupModelStatus.Missing(626)))
        assertTrue(BackupModel.shouldOffer(unreachable, BackupModelStatus.Failed))
        assertTrue(BackupModel.shouldOffer(unreachable, BackupModelStatus.WaitingForWiFi))
        assertFalse(BackupModel.shouldOffer(unreachable, BackupModelStatus.Ready))
        assertFalse(BackupModel.shouldOffer(unreachable, BackupModelStatus.Downloading(0.2)))
        assertFalse(BackupModel.shouldOffer(unreachable, BackupModelStatus.Offline))
        assertFalse(BackupModel.shouldOffer(EngineUnavailability.Kind.HomeServerRejected, BackupModelStatus.Missing(626)))
        assertFalse(BackupModel.shouldOffer(null, BackupModelStatus.Missing(626)))
    }
}
