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
        var result: Result = Result.Refused
        val ran = PhoneModelDownloader.ifNotDownloading(File(modelsRoot, name)) { result = removeBoth(name) }
        return if (ran) result else Result.Refused
    }

    private fun removeBoth(name: String): Result {
        var freed = 0L
        for (file in listOf(File(modelsRoot, "$name.part"), File(modelsRoot, name))) {
            if (!file.isFile) continue
            val size = file.length()
            if (!remove(file)) return Result.Failed
            freed += size
        }
        return Result.Freed(freed)
    }
}
