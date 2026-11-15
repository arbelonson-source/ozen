package com.arbelonson.ozen.core

object QuickPhraseUndo {
    const val SIGHTED_MILLIS = 8_000L

    fun keepMillis(screenReaderOn: Boolean): Long? = if (screenReaderOn) null else SIGHTED_MILLIS

    fun deletedMessage(removed: String, screenReaderOn: Boolean): String =
        if (screenReaderOn) tr("נמחק: %1. אפשר להחזיר.", "Deleted: %1. Undo is available.", listOf(removed)) else tr("נמחק: %1", "Deleted: %1", listOf(removed))
}
