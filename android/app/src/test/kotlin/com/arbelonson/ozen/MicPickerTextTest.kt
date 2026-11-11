package com.arbelonson.ozen

import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.RoutedElsewhere
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MicPickerTextTest {
    private val bottom = AudioInputDescriptor("BuiltInMic:bottom", "SM-X", AudioPortType.BuiltInMic)
    private val back = AudioInputDescriptor("BuiltInMic:back", "SM-X", AudioPortType.BuiltInMic)
    private val lapel = AudioInputDescriptor("usb:lapel", "Lapel mic", AudioPortType.Usb)

    private fun english(block: () -> Unit) = Localization.withLanguage(UILanguage.English, block)

    @Test
    fun `the phone's microphones show as one row named for the phone, not its model code`() = english {
        val rows = MicPickerText.rows(listOf(bottom, back, lapel), selected = null)
        assertEquals(listOf(bottom, lapel), rows)
        assertEquals("This phone’s microphone", MicPickerText.title(bottom))
        assertEquals("Lapel mic", MicPickerText.title(lapel))
        assertEquals("Sound playing on this phone", MicPickerText.title(PhoneSound.entry))
    }

    @Test
    fun `the built-in row kept is the one in use, so it is the one marked`() {
        assertEquals(listOf(back, lapel, PhoneSound.entry), MicPickerText.rows(listOf(bottom, back, lapel, PhoneSound.entry), selected = back))
        assertEquals(listOf(bottom, lapel), MicPickerText.rows(listOf(bottom, back, lapel), selected = lapel))
        assertEquals(listOf(lapel), MicPickerText.rows(listOf(lapel), selected = back))
    }

    @Test
    fun `with captions off the notes say which microphone captions will use, not that one records now`() = english {
        val off = MicPickerText.listNote(listening = false, phoneSound = false)
        assertFalse(off.contains("recording now"), off)
        assertTrue(off.startsWith("The marked microphone is the one captions will use."), off)
        assertEquals(
            "The marked microphone is the one recording now. Headphones or a microphone that connect will appear here automatically.",
            MicPickerText.listNote(listening = true, phoneSound = false),
        )
        assertEquals("The meter is only active while listening.", MicPickerText.meterNote(listening = false, phoneSound = false))
    }

    @Test
    fun `with the phone's sound chosen the notes ask for a video playing, not for speaking into a microphone`() = english {
        val meter = MicPickerText.meterNote(listening = true, phoneSound = true)
        assertFalse(meter.startsWith("Speak and watch the bar move"), meter)
        assertTrue(meter.startsWith("Play a video or a song on the phone and watch the bar move."), meter)
        val list = MicPickerText.listNote(listening = true, phoneSound = true)
        assertFalse(list.contains("microphone is the one recording"), list)
        assertTrue(list.startsWith("Captions now come from the sound playing on this phone."), list)
        assertTrue(MicPickerText.meterNote(listening = true, phoneSound = false).startsWith("Speak and watch the bar move"))
    }

    @Test
    fun `a refusal says to reconnect the one she chose and that the marked one is still in use, not that it records or to unplug`() = english {
        assertEquals(
            listOf("The phone did not switch to “Lapel mic”. Try reconnecting it. The marked microphone is still the one in use."),
            MicPickerText.notices(refused = "Lapel mic", phoneSoundStopped = false),
        )
        assertEquals(emptyList(), MicPickerText.notices(refused = null, phoneSoundStopped = false))
    }

    @Test
    fun `a refusal goes once the phone records from the microphone she tapped after all, as on the iPhone`() {
        assertEquals("Lapel mic", MicPickerText.refusal("Lapel mic", lapel.uid, marked = bottom))
        assertEquals(null, MicPickerText.refusal("Lapel mic", lapel.uid, marked = lapel))
        assertEquals(null, MicPickerText.refusal(null, null, marked = bottom))
    }

    @Test
    fun `a switch the phone made and took back is said in the picker too, which covers the caption screen's banner`() = english {
        assertEquals(
            listOf("Lapel mic isn’t recording", "Captions come from SM-X instead."),
            MicPickerText.notices(refused = null, phoneSoundStopped = false, routed = RoutedElsewhere(picked = lapel, inUse = bottom)),
        )
    }

    @Test
    fun `the phone's sound stopping is said in the picker too, where she is when it happens`() = english {
        assertEquals(
            listOf("Stopped listening to the phone’s sound. Back to the microphone."),
            MicPickerText.notices(refused = null, phoneSoundStopped = true),
        )
    }

    @Test
    fun `every new picker text is translated and none falls back to Hebrew or English`() {
        val texts: (UILanguage) -> List<String> = { language ->
            Localization.withLanguage(language) {
                listOf(
                    MicPickerText.title(bottom),
                    MicPickerText.listNote(listening = false, phoneSound = false),
                    MicPickerText.listNote(listening = true, phoneSound = true),
                    MicPickerText.meterNote(listening = true, phoneSound = true),
                ) + MicPickerText.notices(refused = "Lapel mic", phoneSoundStopped = false)
            }.map { it.trimStart('‎', '‏') }
        }
        val hebrew = texts(UILanguage.Hebrew)
        val english = texts(UILanguage.English)
        for (language in UILanguage.entries) {
            if (language == UILanguage.Hebrew || language == UILanguage.English) continue
            texts(language).forEachIndexed { index, shown ->
                assertNotEquals(hebrew[index], shown, "$language fell back to Hebrew")
                assertNotEquals(english[index], shown, "$language has no translation for ${english[index]}")
            }
        }
    }
}
