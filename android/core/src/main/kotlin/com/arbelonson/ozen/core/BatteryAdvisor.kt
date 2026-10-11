package com.arbelonson.ozen.core

import kotlin.math.roundToInt

sealed class BatteryWarning {
    abstract val percent: Int

    data class Low(override val percent: Int) : BatteryWarning()

    data class Critical(override val percent: Int) : BatteryWarning()

    val notificationContent: AlertNotificationContent
        get() = when (this) {
            is Low -> AlertNotificationContent(
                identifier = "battery",
                title = tr("הסוללה ב-%1%", "Battery at %1%", listOf("$percent")),
                body = tr(
                    "הכתוביות וההתראות ממשיכות, אבל כדאי לחבר למטען.",
                    "Captions and alerts keep running, but it's worth plugging in.",
                ),
                threadIdentifier = "status",
                isUrgent = false,
            )
            is Critical -> AlertNotificationContent(
                identifier = "battery",
                title = tr("הסוללה ב-%1%", "Battery at %1%", listOf("$percent")),
                body = tr(
                    "הטלפון עלול להיכבות, ואיתו הכתוביות וההתראות. חברו למטען.",
                    "The phone might turn off, and captions and alerts with it. Plug it in.",
                ),
                threadIdentifier = "status",
                isUrgent = true,
            )
        }
}

class BatteryAdvisor(
    var lowThreshold: Float = 0.20f,
    var criticalThreshold: Float = 0.10f,
    var rearmMargin: Float = 0.05f,
) {
    private var warnedLow = false
    private var warnedCritical = false

    fun update(level: Float?, isPluggedIn: Boolean): BatteryWarning? {
        if (level == null || level < 0) return null
        if (level > lowThreshold + rearmMargin) warnedLow = false
        if (level > criticalThreshold + rearmMargin) warnedCritical = false
        if (isPluggedIn) return null

        val percent = (level * 100).roundToInt()
        if (level <= criticalThreshold && !warnedCritical) {
            warnedCritical = true
            warnedLow = true
            return BatteryWarning.Critical(percent)
        }
        if (level <= lowThreshold && !warnedLow) {
            warnedLow = true
            return BatteryWarning.Low(percent)
        }
        return null
    }

    companion object {
        /**
         * Whether battery readings count in [phase]: from asking for
         * captions until they are stopped or paused. A failure counts too:
         * captions retrying, or the microphone kept on for sounds alone, run
         * the battery down, and the moment between two attempts took the
         * warning down, never to come back once given.
         */
        fun watches(phase: PipelinePhase): Boolean = when (phase) {
            PipelinePhase.Idle, PipelinePhase.Paused -> false
            PipelinePhase.RequestingMicrophonePermission, is PipelinePhase.PreparingEngine, PipelinePhase.StartingAudio, PipelinePhase.Listening, is PipelinePhase.Failed -> true
        }
    }
}
