package com.arbelonson.ozen

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.TorchOwnership
import com.arbelonson.ozen.core.TorchRunner

internal class CameraLight(private val context: Context) : TorchRunner.Torch {
    override fun set(on: Boolean): Boolean = switch(context, on)

    companion object {
        var isAvailable: (Context) -> Boolean = { context -> PhoneTorch.camera(context) != null }
        var switch: (Context, Boolean) -> Boolean = PhoneTorch::set
        var watch: (Context, Boolean, () -> Unit) -> Unit = PhoneTorch::watch
    }
}

private object PhoneTorch {
    @Volatile private var cameraId: String? = null
    @Volatile private var looked = false
    private val ownership = TorchOwnership()
    private var callback: CameraManager.TorchCallback? = null

    fun camera(context: Context): String? {
        if (looked) return cameraId
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val found = try {
            val lit = manager.cameraIdList.filter { manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            lit.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: lit.firstOrNull()
        } catch (_: CameraAccessException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        cameraId = found
        looked = true
        return found
    }

    fun set(context: Context, on: Boolean): Boolean {
        if (!on && !ownership.needsTurningOff()) return true
        if (on && !ownership.mayTurnOn()) return false
        val id = camera(context) ?: return false
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        val succeeded = try {
            manager.setTorchMode(id, on)
            true
        } catch (_: CameraAccessException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
        ownership.switched(on, succeeded)
        return succeeded
    }

    @Synchronized
    fun watch(context: Context, on: Boolean, unavailable: () -> Unit) {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        callback?.let { manager.unregisterTorchCallback(it) }
        callback = null
        if (!on || camera(context) == null) return
        ownership.watching()
        callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) ownership.changed(enabled)
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id != cameraId) return
                ownership.unavailable()
                unavailable()
            }
        }.also { manager.registerTorchCallback(it, Handler(Looper.getMainLooper())) }
    }
}
