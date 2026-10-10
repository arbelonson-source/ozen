package com.arbelonson.ozen.core

sealed class BackupModelStatus {
    data object NotNeeded : BackupModelStatus()

    data object Ready : BackupModelStatus()

    data class Missing(val megabytes: Int) : BackupModelStatus()

    data class Downloading(val fraction: Double) : BackupModelStatus()

    data object WaitingForWiFi : BackupModelStatus()

    data object Offline : BackupModelStatus()

    data class NotEnoughRoom(val megabytes: Int) : BackupModelStatus()

    data object Failed : BackupModelStatus()
}

object BackupModel {
    fun status(
        engine: TranscriptionEngineKind,
        installed: Boolean,
        sizeMegabytes: Int,
        downloading: Double?,
        failed: Boolean,
        shortfallMegabytes: Int?,
        network: NetworkConditions?,
        allowCellular: Boolean,
    ): BackupModelStatus {
        if (engine != TranscriptionEngineKind.HomeServer && engine != TranscriptionEngineKind.Cloud) return BackupModelStatus.NotNeeded
        if (installed) return BackupModelStatus.Ready
        if (downloading != null) return BackupModelStatus.Downloading(downloading)
        if (shortfallMegabytes != null) return BackupModelStatus.NotEnoughRoom(shortfallMegabytes)
        return when (ModelDownloadGate.decide(network, allowCellular)) {
            ModelDownloadGate.Decision.Offline -> BackupModelStatus.Offline
            ModelDownloadGate.Decision.WaitForWiFi -> BackupModelStatus.WaitingForWiFi
            ModelDownloadGate.Decision.Proceed -> if (failed) BackupModelStatus.Failed else BackupModelStatus.Missing(sizeMegabytes)
        }
    }

    fun shouldOffer(unreachable: EngineUnavailability.Kind?, status: BackupModelStatus): Boolean {
        if (unreachable != EngineUnavailability.Kind.HomeServerUnreachable) return false
        return status is BackupModelStatus.Missing || status == BackupModelStatus.Failed || status == BackupModelStatus.WaitingForWiFi
    }

    fun canStart(status: BackupModelStatus): Boolean =
        status is BackupModelStatus.Missing || status == BackupModelStatus.Failed
}
