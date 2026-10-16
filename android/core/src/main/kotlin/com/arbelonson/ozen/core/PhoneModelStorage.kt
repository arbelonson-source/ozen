package com.arbelonson.ozen.core

import java.io.File

data class StoredPhoneModel(val name: String, val bytes: Long, val active: Boolean)

class PhoneModelStorage(private val modelsRoot: File, private val activeFileName: String) {
    private val modelNames: Set<String> =
        PhoneModelCatalog.managedFiles

    private fun isManaged(name: String): Boolean =
        name in modelNames || (name.endsWith(PART) && name.removeSuffix(PART) in modelNames)

    private fun isActive(name: String): Boolean = name.removeSuffix(PART) == activeFileName

    fun list(): List<StoredPhoneModel> =
        (modelsRoot.listFiles() ?: emptyArray())
            .filter { it.isFile && isManaged(it.name) }
            .sortedBy { it.name }
            .map { StoredPhoneModel(it.name, it.length(), isActive(it.name)) }

    fun delete(name: String): Long {
        require(isManaged(name)) { "Not a phone model file: $name" }
        check(!isActive(name)) { "The model in use cannot be deleted: $name" }
        val base = name.removeSuffix(PART)
        var freed = 0L
        val targets = if (name.endsWith(PART)) listOf(File(modelsRoot, name)) else listOf(File(modelsRoot, base), File(modelsRoot, base + PART))
        for (file in targets) {
            if (!file.isFile) continue
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    private companion object {
        const val PART = ".part"
    }
}
