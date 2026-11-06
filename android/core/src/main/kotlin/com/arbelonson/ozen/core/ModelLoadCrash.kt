package com.arbelonson.ozen.core

object ModelLoadCrash {
    fun lighter(
        current: PhoneModel,
        language: UILanguage,
        totalMemoryBytes: Long?,
        candidates: List<PhoneModel> = PhoneModelCatalog.offered(language),
    ): PhoneModel? {
        val lighter = candidates.filter { it.bytes < current.bytes }
        val fitting = lighter.filter { totalMemoryBytes == null || it.comfortableRamBytes <= totalMemoryBytes }
        return fitting.ifEmpty { lighter }.maxWithOrNull(compareBy<PhoneModel> { it.quality(language) }.thenBy { -it.bytes })
    }

    fun marker(file: String) = "loading $file"

    fun offer(marked: String?, current: PhoneModel, language: UILanguage, totalMemoryBytes: Long?): PhoneModel? =
        if (marked == marker(current.file)) lighter(current, language, totalMemoryBytes) else null
}
