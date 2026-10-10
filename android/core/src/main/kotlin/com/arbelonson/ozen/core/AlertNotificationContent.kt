package com.arbelonson.ozen.core

data class AlertNotificationContent(
    val identifier: String,
    val title: String,
    val body: String,
    val threadIdentifier: String,
    val isUrgent: Boolean,
)
