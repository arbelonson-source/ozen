package com.arbelonson.ozen

import android.content.Context
import android.os.storage.StorageManager
import com.arbelonson.ozen.core.StorageSpaceGate
import java.io.File
import java.io.IOException

class DeviceStorage(context: Context) {
    private val folder: File = context.filesDir
    private val manager = context.getSystemService(StorageManager::class.java)

    private fun uuid() = manager.getUuidForPath(folder)

    fun freeBytes(): Long {
        val allocatable = try {
            manager.getAllocatableBytes(uuid())
        } catch (_: IOException) {
            null
        }
        return StorageSpaceGate.roomBytes(folder.usableSpace, allocatable)
    }

    fun makeRoom(bytes: Long) {
        try {
            manager.allocateBytes(uuid(), bytes)
        } catch (_: IOException) {
        }
    }
}
