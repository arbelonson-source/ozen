package com.arbelonson.ozen.core

object AndroidInputChoice {
    fun systemDefault(inputs: List<AudioInputDescriptor>): String? =
        (inputs.firstOrNull { it.portType == AudioPortType.Wired } ?: inputs.firstOrNull { it.portType == AudioPortType.Usb })?.uid

    fun resolve(available: List<AudioInputDescriptor>, preferredUID: String?, lastSelectedUID: String?): String? =
        AudioRoutePolicy.resolveSelection(
            available = available,
            preferredUID = preferredUID,
            currentUID = systemDefault(available) ?: lastSelectedUID,
        )
}
