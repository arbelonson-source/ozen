package com.arbelonson.ozen.core

object MobileDataNote {
    fun shows(engine: TranscriptionEngineKind?, coveringForCloud: Boolean, network: NetworkConditions?): Boolean =
        !coveringForCloud &&
            (engine == TranscriptionEngineKind.HomeServer || engine == TranscriptionEngineKind.Cloud) &&
            network?.isExpensive == true
}
