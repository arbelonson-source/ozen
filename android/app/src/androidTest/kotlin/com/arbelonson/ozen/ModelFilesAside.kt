package com.arbelonson.ozen

import com.arbelonson.ozen.core.PhoneModelCatalog
import java.io.File

class ModelFilesAside(private val folder: File) {
    private val held = ArrayList<Pair<File, File>>()

    fun hold() {
        for (model in PhoneModelCatalog.all) {
            val file = File(folder, model.file)
            val aside = File(folder, model.file + ".held")
            if (file.isFile && file.renameTo(aside)) held += aside to file
        }
    }

    fun putBack() {
        for ((aside, file) in held) aside.renameTo(file)
        held.clear()
    }
}
