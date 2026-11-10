package com.arbelonson.ozen.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AwaySoundRecordTest {
    private fun alert(identifier: String, at: Double) =
        SoundAlert(event = assertNotNull(SoundEventCatalog.event(identifier)), confidence = 0.9, timestamp = at)

    @Test
    fun `a sound kept while away comes back after the app was ended, with its time and id`() {
        val smoke = alert("smoke_detector", 1_000.0)
        val back = assertNotNull(AwaySoundRecord.decode(AwaySoundRecord.encode(smoke), now = 1_000.0 + 3_600))
        assertEquals(smoke.event, back.event)
        assertEquals(smoke.id, back.id)
        assertEquals(1_000.0, back.timestamp)
    }

    @Test
    fun `a record too old to name its time plainly, from the future, of an unknown sound or garbled is dropped`() {
        val record = AwaySoundRecord.encode(alert("door_bell", 1_000.0))
        val id = UUID.randomUUID()
        assertNull(AwaySoundRecord.decode(record, now = 1_000.0 + AwaySoundRecord.KEPT_SECONDS + 1))
        assertNull(AwaySoundRecord.decode(record, now = 1_000.0 - 301))
        assertNull(AwaySoundRecord.decode("v1\tno_such_sound\t1000.0\t$id", now = 1_000.0))
        assertNull(AwaySoundRecord.decode("v2\tdoor_bell\t1000.0\t$id", now = 1_000.0))
        assertNull(AwaySoundRecord.decode("v1\tdoor_bell\tnot a time\t$id", now = 1_000.0))
        assertNull(AwaySoundRecord.decode("v1\tdoor_bell\t1000.0\tnot an id", now = 1_000.0))
        assertNull(AwaySoundRecord.decode("garbage", now = 1_000.0))
        assertNull(AwaySoundRecord.decode(null, now = 1_000.0))
        assertNotNull(AwaySoundRecord.decode(record, now = 1_000.0 + AwaySoundRecord.KEPT_SECONDS), "twelve hours exactly is still kept")
    }
}
