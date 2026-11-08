package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutedInputTest {
    private val builtIn = AudioInputDescriptor("builtInMic:", "Phone microphone", AudioPortType.BuiltInMic)
    private val lapel = AudioInputDescriptor("bluetooth:aa", "Lapel Pro", AudioPortType.Bluetooth)
    private val listed = listOf(7 to builtIn, 12 to lapel)

    @Test
    fun `a routed device id maps to the listed input with that id`() {
        assertEquals(lapel, routedInput(12, listed))
        assertEquals(builtIn, routedInput(7, listed))
    }

    @Test
    fun `an id that is not listed, or no routed device, maps to nothing`() {
        assertNull(routedInput(99, listed))
        assertNull(routedInput(null, listed))
    }
}
