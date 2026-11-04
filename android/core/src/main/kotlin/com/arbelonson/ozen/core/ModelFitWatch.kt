package com.arbelonson.ozen.core

object ModelFitWatch {
    const val PASSES_TO_SAVE = 20

    fun counts(passSeconds: Double, heat: DeviceHeat, lowPowerMode: Boolean, engine: TranscriptionEngineKind): Boolean =
        engine == TranscriptionEngineKind.WhisperKit &&
            passSeconds.isFinite() && passSeconds > 0 &&
            heat.rawValue < DeviceHeat.Serious.rawValue &&
            !lowPowerMode

    fun shouldSave(countedPasses: Int): Boolean = countedPasses >= PASSES_TO_SAVE

    fun measurement(model: PhoneModel, countedPasses: Int, totalSeconds: Double, now: Double): LiveMeasurement? {
        if (!shouldSave(countedPasses) || !totalSeconds.isFinite() || totalSeconds <= 0) return null
        val average = totalSeconds / countedPasses
        return LiveMeasurement(model.file, average, countedPasses, model.passGflop / (average * PhoneModelEstimate.REAL_PASS_SHARE), now)
    }

    fun dismissalKey(variant: String, device: String): String = "$variant|$device"

    fun offer(
        measurement: LiveMeasurement?,
        current: PhoneModel,
        language: UILanguage,
        totalMemoryBytes: Long?,
        dismissed: Collection<String>,
        device: String,
    ): PhoneModel? {
        if (measurement == null || measurement.file != current.file) return null
        if (dismissalKey(current.variant, device) in dismissed) return null
        if (PhoneModelEstimate.fit(current, measurement.gflops, totalMemoryBytes) == PhoneModelFit.KeepsUp) return null
        val lighter = PhoneModelEstimate.recommend(language, measurement.gflops, totalMemoryBytes)
        if (lighter.file == current.file || lighter.passGflop >= current.passGflop) return null
        return lighter.takeIf { dismissalKey(it.variant, device) !in dismissed }
    }
}
