package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorchOwnershipTest {
    @Test
    fun `a flashlight the person turned on is never blinked or turned off`() {
        val torch = TorchOwnership()
        torch.watching()
        torch.changed(enabled = true)
        assertFalse(torch.mayTurnOn())
        assertFalse(torch.needsTurningOff())
    }

    @Test
    fun `the phone confirming Ozen's own flash late does not count as the person's`() {
        val torch = TorchOwnership()
        torch.watching()
        torch.switched(on = true, succeeded = true)
        torch.switched(on = false, succeeded = true)
        torch.changed(enabled = true)
        assertTrue(torch.mayTurnOn())
        torch.changed(enabled = false)
        assertTrue(torch.mayTurnOn())
    }

    @Test
    fun `an off that failed is tried again by the last off`() {
        val torch = TorchOwnership()
        torch.switched(on = true, succeeded = true)
        torch.switched(on = false, succeeded = false)
        assertTrue(torch.needsTurningOff())
        torch.switched(on = false, succeeded = true)
        assertFalse(torch.needsTurningOff())
    }

    @Test
    fun `a flashlight turned on while the switch was off is the person's once watching starts again`() {
        val torch = TorchOwnership()
        torch.watching()
        torch.switched(on = true, succeeded = true)
        torch.changed(enabled = true)
        torch.switched(on = false, succeeded = true)
        torch.watching()
        torch.changed(enabled = true)
        assertFalse(torch.mayTurnOn())
        assertFalse(torch.needsTurningOff())
    }

    @Test
    fun `another app taking the camera ends Ozen's hold on the light`() {
        val torch = TorchOwnership()
        torch.switched(on = true, succeeded = true)
        torch.unavailable()
        assertFalse(torch.needsTurningOff())
        assertTrue(torch.mayTurnOn())
    }
}
