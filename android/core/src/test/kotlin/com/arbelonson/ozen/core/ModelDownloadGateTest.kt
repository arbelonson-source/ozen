package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadGateTest {
    @Test
    fun `Wi-Fi goes ahead, cellular and Low Data Mode wait unless allowed, offline is offline`() {
        assertEquals(ModelDownloadGate.Decision.Proceed, ModelDownloadGate.decide(NetworkConditions.wifi, allowCellular = false))
        assertEquals(ModelDownloadGate.Decision.WaitForWiFi, ModelDownloadGate.decide(NetworkConditions.cellular, allowCellular = false))
        assertEquals(ModelDownloadGate.Decision.Proceed, ModelDownloadGate.decide(NetworkConditions.cellular, allowCellular = true))
        val lowData = NetworkConditions(isConnected = true, isConstrained = true)
        assertEquals(ModelDownloadGate.Decision.WaitForWiFi, ModelDownloadGate.decide(lowData, allowCellular = false))
        assertEquals(ModelDownloadGate.Decision.Proceed, ModelDownloadGate.decide(lowData, allowCellular = true))
        assertEquals(ModelDownloadGate.Decision.Offline, ModelDownloadGate.decide(NetworkConditions.offline, allowCellular = true))
    }

    @Test
    fun `before the system reports a connection, the download itself finds out`() {
        assertEquals(ModelDownloadGate.Decision.Proceed, ModelDownloadGate.decide(null, allowCellular = false))
    }

    @Test
    fun `a change of connection is worth a retry only when the gate would now let the download through`() {
        assertEquals(true, ModelDownloadGate.canRetryDownload(NetworkConditions.wifi, allowCellular = false))
        assertEquals(false, ModelDownloadGate.canRetryDownload(NetworkConditions.cellular, allowCellular = false))
        assertEquals(true, ModelDownloadGate.canRetryDownload(NetworkConditions.cellular, allowCellular = true))
        assertEquals(false, ModelDownloadGate.canRetryDownload(NetworkConditions.offline, allowCellular = true))
    }
}
