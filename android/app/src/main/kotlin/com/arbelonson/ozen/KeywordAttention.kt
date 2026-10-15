package com.arbelonson.ozen

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.arbelonson.ozen.core.AlertSuggestions
import com.arbelonson.ozen.core.AlertVibration
import com.arbelonson.ozen.core.KeywordAttentionPolicy
import com.arbelonson.ozen.core.KeywordHit
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SoundEvent
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class HeardWord(val id: UUID, val said: String, val at: Double, val line: UUID? = null)

class KeywordAttention(private val policy: KeywordAttentionPolicy = KeywordAttentionPolicy()) {
    fun claim(hits: List<KeywordHit>): HeardWord? =
        hits.filter { policy.claimAttention(it.match.alertID, it.timestamp) }
            .firstOrNull()
            ?.let { HeardWord(it.id, AlertSuggestions.said(it.match, Localization.language), it.timestamp, line = it.segmentID) }
}

fun AlertVibration.waveform(): Pair<LongArray, IntArray> {
    val timings = mutableListOf<Long>()
    val amplitudes = mutableListOf<Int>()
    var at = 0.0
    for (pulse in pulses.sortedBy { it.start }) {
        timings += ((pulse.start - at) * 1_000).roundToLong().coerceAtLeast(0)
        amplitudes += 0
        timings += (pulse.duration * 1_000).roundToLong()
        amplitudes += (pulse.intensity * 255).roundToInt().coerceIn(1, 255)
        at = pulse.end
    }
    return timings.toLongArray() to amplitudes.toIntArray()
}

fun phoneVibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
} else {
    context.getSystemService(Vibrator::class.java)
}

object Buzz {
    enum class Usage { Alarm, Notification }

    fun usageFor(importance: SoundEvent.Importance?): Usage =
        if (importance == SoundEvent.Importance.Critical) Usage.Alarm else Usage.Notification

    var play: (Context, AlertVibration, SoundEvent.Importance?) -> Unit = { context, vibration, importance ->
        val vibrator = phoneVibrator(context)
        val (timings, amplitudes) = vibration.waveform()
        if (vibrator?.hasVibrator() == true) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            val usage = usageFor(importance)
            if (Build.VERSION.SDK_INT >= 33) {
                val kind = if (usage == Usage.Alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
                vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(kind).build())
            } else {
                val kind = if (usage == Usage.Alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(kind).build())
            }
        }
    }
}
