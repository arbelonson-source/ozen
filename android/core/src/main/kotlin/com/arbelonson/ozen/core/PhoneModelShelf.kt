package com.arbelonson.ozen.core

object PhoneModelShelf {
    private const val PART = ".part"

    fun partials(entries: List<StoredPhoneModel>): Map<String, Long> {
        val whole = entries.filter { !it.name.endsWith(PART) }.map { it.name }.toSet()
        return entries
            .filter { it.name.endsWith(PART) && it.name.removeSuffix(PART) !in whole }
            .associate { it.name.removeSuffix(PART) to it.bytes }
    }

    fun notOffered(language: UILanguage, installed: Set<String>, partial: Map<String, Long>): List<PhoneModel> {
        val offered = PhoneModelCatalog.offered(language).map { it.file }.toSet()
        return PhoneModelCatalog.all.filter { it.file !in offered && (it.file in installed || it.file in partial) }
    }

    fun canDelete(file: String, installed: Set<String>, partial: Map<String, Long>, inUse: Boolean, downloading: String?): Boolean =
        !inUse && downloading != file && (file in installed || file in partial)
}
