package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class HistoryRereadsTest {
    @Test
    fun `a reading made just before a star, that comes back after the star's own reading was shown, is left out`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        val begun = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val older = async {
            rereads.read({
                begun.complete(Unit)
                gate.await()
                "before the star"
            }) { shown += it }
        }
        begun.await()
        rereads.read({ "starred" }) { shown += it }
        gate.complete(Unit)
        assertTrue(older.await())
        assertEquals(listOf("starred"), shown)
    }

    @Test
    fun `readings that come back in the order they began are each shown`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        rereads.read({ "the bus leaves at six" }) { shown += it }
        rereads.read({ "the bus leaves at six, starred" }) { shown += it }
        assertEquals(listOf("the bus leaves at six", "the bus leaves at six, starred"), shown)
    }

    @Test
    fun `a reading that can't read the conversation just now keeps what is shown`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        assertFalse(rereads.read<String>({ null }) { shown += it })
        assertEquals(emptyList(), shown)
    }

    @Test
    fun `after a change, a reading that can't read the conversation just now is tried again every few seconds until it reads`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        var reads = 0
        rereads.afterChange(sleep = { waits += it }, load = {
            reads++
            if (reads < 3) null else "Shira"
        }) { shown += it }
        assertEquals(listOf("Shira"), shown)
        assertEquals(3, reads)
        assertEquals(listOf(3_000L, 3_000L), waits)
    }

    @Test
    fun `a change is not read again once a reading begun after it has been shown`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        var reads = 0
        rereads.afterChange<String>(sleep = { rereads.read({ "read every twenty seconds" }) { shown += it } }, load = {
            reads++
            null
        }) { shown += it }
        assertEquals(1, reads)
        assertEquals(listOf("read every twenty seconds"), shown)
    }

    @Test
    fun `a fix that couldn't be read back is read again a few seconds later, not at once`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        var reads = 0
        assertFalse(rereads.read<String>({ reads++; null }) { shown += it })
        rereads.readAgainSoon(sleep = { waits += it }, load = { reads++; "Shira" }) { shown += it }
        assertEquals(listOf("Shira"), shown)
        assertEquals(2, reads)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `closing the screen while a change waits to be read again stops the readings`() = runBlocking {
        val rereads = HistoryRereads()
        val shown = mutableListOf<String>()
        var reads = 0
        val stopped = runCatching {
            rereads.afterChange<String>(sleep = { throw CancellationException("closed") }, load = {
                reads++
                null
            }) { shown += it }
        }
        assertTrue(stopped.exceptionOrNull() is CancellationException)
        assertEquals(1, reads)
        assertEquals(emptyList(), shown)
    }
}
