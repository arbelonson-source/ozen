package com.arbelonson.ozen

import android.content.res.AssetManager
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object ModelAssets {
    fun read(assets: AssetManager, name: String): ByteBuffer = try {
        mapped(assets, name)
    } catch (compressed: FileNotFoundException) {
        copied(assets, name)
    }

    fun mapped(assets: AssetManager, name: String): ByteBuffer = assets.openFd(name).use { file ->
        FileInputStream(file.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength)
        }
    }

    fun copied(assets: AssetManager, name: String): ByteBuffer {
        val bytes = assets.open(name).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buffer.rewind()
        return buffer
    }
}
