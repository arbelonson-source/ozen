package com.arbelonson.ozen.core

import java.io.File

class PhoneModelDeletion(
    private val modelsRoot: File,
    private val activeFileName: String,
    private val remove: (File) -> Boolean = { it.delete() },
) {
    sealed interface Result {
        data class Freed(val bytes: Long) : Result

        data object Refused : Result

        data object Failed : Result
    }

    fun delete(name: String): Result {
        if (name !in PhoneModelCatalog.managedFiles || name == activeFileName) return Result.Refused
        var freed = 0L
        for (file in listOf(File(modelsRoot, name), File(modelsRoot, "$name.part"))) {
            if (!file.isFile) continue
            val size = file.length()
            if (!remove(file)) return Result.Failed
            freed += size
        }
        return Result.Freed(freed)
    }
}
