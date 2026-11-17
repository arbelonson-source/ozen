package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationChannelNamesTest {
    @Test
    fun onlyTheChannelsThatAlreadyExistAreUpdated() {
        val updated = mutableListOf<String>()
        NotificationChannelNames.refresh(listOf("alerts", "alarms", "listening"), { it }, { it != "alarms" }, { updated += it })
        assertEquals(listOf("alerts", "listening"), updated)
    }

    @Test
    fun nothingIsMadeWhenNoChannelExists() {
        val updated = mutableListOf<String>()
        NotificationChannelNames.refresh(listOf("alerts"), { it }, { false }, { updated += it })
        assertEquals(emptyList(), updated)
    }
}
