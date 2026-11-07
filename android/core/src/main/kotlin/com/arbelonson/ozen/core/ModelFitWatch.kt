package com.arbelonson.ozen.core

object ModelFitWatch {
    const val PASSES_TO_SAVE = 20

    fun counts(passSeconds: Double, heat: DeviceHeat, lowPowerMode: Boolean, engine: TranscriptionEngineKind): Boolean =
        engine == TranscriptionEngineKind.WhisperKit &&
            passSeconds.isFinite() && passSeconds > 0 &&
            heat.rawValue < DeviceHeat.Serious.rawValue &&
            !lowPowerMode

    fun countsInLowPower(passSeconds: Double, heat: DeviceHeat, lowPowerMode: Boolean, engine: TranscriptionEngineKind): Boolean =
        engine == TranscriptionEngineKind.WhisperKit &&
            passSeconds.isFinite() && passSeconds > 0 &&
            heat.rawValue < DeviceHeat.Serious.rawValue &&
            lowPowerMode

    // A timing taken in battery saver only stands in when no normal timing of
    // this model on this phone is saved: it must never overwrite one.
    fun keeps(saved: LiveMeasurement?, new: LiveMeasurement): Boolean =
        !new.lowPower || saved == null || saved.lowPower || saved.device != new.device || saved.file != new.file

    fun shouldSave(countedPasses: Int): Boolean = countedPasses >= PASSES_TO_SAVE

    fun measurement(model: PhoneModel, countedPasses: Int, totalSeconds: Double, now: Double, device: String? = null, lowPower: Boolean = false): LiveMeasurement? {
        if (!shouldSave(countedPasses) || !totalSeconds.isFinite() || totalSeconds <= 0) return null
        val average = totalSeconds / countedPasses
        return LiveMeasurement(model.file, average, countedPasses, model.passGflop / (average * PhoneModelEstimate.REAL_PASS_SHARE), now, fitEligible = true, device = device, lowPower = lowPower)
    }

    fun fitsOnPhone(installed: Boolean, shortfallMegabytes: Int?): Boolean = installed || shortfallMegabytes == null

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
        if (!measurement.fitEligible || !shouldSave(measurement.passes)) return null
        if (dismissalKey(current.variant, device) in dismissed) return null
        if (PhoneModelEstimate.fit(current, measurement.gflops, totalMemoryBytes) == PhoneModelFit.KeepsUp) return null
        val lighter = PhoneModelEstimate.recommend(language, measurement.gflops, totalMemoryBytes)
        if (lighter.file == current.file || lighter.passGflop >= current.passGflop) return null
        if (PhoneModelEstimate.fit(lighter, measurement.gflops, totalMemoryBytes) == PhoneModelFit.TooSlow) return null
        return lighter.takeIf { dismissalKey(it.variant, device) !in dismissed }
    }
}
