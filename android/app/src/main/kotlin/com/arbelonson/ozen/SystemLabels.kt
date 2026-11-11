package com.arbelonson.ozen

import android.annotation.SuppressLint
import android.content.Context

object SystemLabels {
    const val ALL_APPS = "filter_all_apps"
    const val DONT_OPTIMIZE = "ignore_optimizations_on"
    const val ALLOW_BACKGROUND = "manager_battery_usage_allow_background_usage_title"
    const val UNRESTRICTED = "manager_battery_usage_unrestricted_title"
    const val OVERRIDE_DND = "app_notification_override_dnd_title"

    private const val SETTINGS = "com.android.settings"

    var lookup: (Context, String) -> String? = { context, name -> fromSettings(context, name) }

    @SuppressLint("DiscouragedApi")
    fun fromSettings(context: Context, name: String): String? =
        try {
            val resources = context.packageManager.getResourcesForApplication(SETTINGS)
            val id = resources.getIdentifier(name, "string", SETTINGS)
            if (id == 0) null else resources.getString(id).trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    fun inSentence(label: String, rightToLeftSentence: Boolean): String {
        val strong = label.asSequence().map { Character.getDirectionality(it) }.firstOrNull {
            it == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
                it == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                it == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        } ?: return label
        val rightToLeftLabel = strong != Character.DIRECTIONALITY_LEFT_TO_RIGHT
        return if (rightToLeftLabel == rightToLeftSentence) label else "\u2068$label\u2069"
    }
}
