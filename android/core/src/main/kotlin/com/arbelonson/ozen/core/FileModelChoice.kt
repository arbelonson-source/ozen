package com.arbelonson.ozen.core

object FileModelChoice {
    fun downloaded(language: UILanguage, installed: Set<String>): List<PhoneModel> {
        val here = PhoneModelCatalog.offered(language).filter { it.file in installed }
        return here.sortedByDescending { it.quality(language) }
    }

    fun pick(language: UILanguage, installed: Set<String>, totalMemoryBytes: Long? = null): PhoneModel? {
        val here = downloaded(language, installed)
        if (totalMemoryBytes == null) return here.firstOrNull()
        return here.firstOrNull { it.comfortableRamBytes <= totalMemoryBytes } ?: here.minByOrNull { it.bytes }
    }

    fun toDownload(language: UILanguage): PhoneModel = PhoneModelCatalog.recommended(language)
}
