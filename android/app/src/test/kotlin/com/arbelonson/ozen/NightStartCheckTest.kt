package com.arbelonson.ozen

import com.arbelonson.ozen.NightStartCheck.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class NightStartCheckTest {
    private fun found(battery: Boolean = true, dnd: Boolean = true, allowed: Boolean = true, postable: Boolean = true, notifications: Boolean = true) =
        NightStartCheck.problems(battery, dnd, allowed, postable, notifications)

    @Test
    fun `nothing is wrong when all three are right`() {
        assertEquals(emptyList(), found())
    }

    @Test
    fun `each of the three problems is named`() {
        assertEquals(listOf(Problem.Battery), found(battery = false))
        assertEquals(listOf(Problem.DoNotDisturb), found(dnd = false))
        assertEquals(listOf(Problem.EmergencySilent), found(dnd = false, allowed = false, postable = true))
        assertEquals(listOf(Problem.EmergencyOff), found(dnd = false, allowed = false, postable = false))
    }

    @Test
    fun `battery and a muted channel come together, in that order`() {
        assertEquals(listOf(Problem.Battery, Problem.EmergencyOff), found(battery = false, dnd = false, allowed = false, postable = false))
    }

    @Test
    fun `a first start shows what is wrong`() {
        assertEquals(listOf(Problem.Battery), NightStartCheck.toShow(listOf(Problem.Battery), emptySet()))
    }

    @Test
    fun `a closed card is not shown again for the same problem`() {
        assertEquals(emptyList(), NightStartCheck.toShow(listOf(Problem.Battery), setOf(Problem.Battery)))
        assertEquals(emptyList(), NightStartCheck.toShow(listOf(Problem.Battery), setOf(Problem.Battery, Problem.DoNotDisturb)))
    }

    @Test
    fun `a new problem brings the card back with everything still wrong`() {
        val both = listOf(Problem.Battery, Problem.DoNotDisturb)
        assertEquals(both, NightStartCheck.toShow(both, setOf(Problem.Battery)))
    }

    @Test
    fun `nothing wrong shows nothing`() {
        assertEquals(emptyList(), NightStartCheck.toShow(emptyList(), emptySet()))
    }

    @Test
    fun `a fixed problem leaves a card that is up`() {
        assertEquals(listOf(Problem.DoNotDisturb), NightStartCheck.stillOpen(listOf(Problem.Battery, Problem.DoNotDisturb), listOf(Problem.DoNotDisturb)))
    }

    @Test
    fun `problems survive being saved as text`() {
        val list = listOf(Problem.Battery, Problem.EmergencySilent)
        assertEquals(list, NightStartCheck.decode(NightStartCheck.encode(list)))
        assertEquals(emptyList(), NightStartCheck.decode(""))
    }

    @Test
    fun `all notifications off is its own problem, not a muted channel`() {
        assertEquals(listOf(Problem.NotificationsOff), found(dnd = false, allowed = false, postable = false, notifications = false))
    }

    @Test
    fun `a fixed dismissal is forgotten so the problem shows again`() {
        assertEquals(listOf(Problem.Battery), NightStartCheck.stillDismissed(listOf(Problem.Battery, Problem.DoNotDisturb), listOf(Problem.Battery)))
    }

    @Test
    fun `emergency alerts going from off to silent keep the card with the new words`() {
        assertEquals(listOf(Problem.EmergencySilent), NightStartCheck.stillOpen(listOf(Problem.EmergencyOff), listOf(Problem.EmergencySilent)))
    }

    @Test
    fun `silent alerts that are then fixed leave the card`() {
        assertEquals(emptyList(), NightStartCheck.stillOpen(listOf(Problem.EmergencySilent), emptyList()))
    }

    @Test
    fun `notifications turned back on but the channel muted keep the card`() {
        assertEquals(listOf(Problem.EmergencyOff), NightStartCheck.stillOpen(listOf(Problem.NotificationsOff), listOf(Problem.EmergencyOff)))
    }

    @Test
    fun `fixing the battery does not bring in a problem from the other kind`() {
        assertEquals(listOf(Problem.Battery), NightStartCheck.stillOpen(listOf(Problem.Battery), listOf(Problem.Battery, Problem.DoNotDisturb)))
    }
}
