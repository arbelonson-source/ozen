package com.arbelonson.ozen.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TorchRunnerTest {
    private class FakeTorch(private val scope: TestScope, var refuses: Boolean = false) : TorchRunner.Torch {
        val calls = mutableListOf<Pair<Long, Boolean>>()
        var isLit = false

        override fun set(on: Boolean): Boolean {
            calls += scope.testScheduler.currentTime to on
            if (on && refuses) return false
            isLit = on
            return true
        }
    }

    private val critical = SoundEvent.Importance.Critical
    private val high = SoundEvent.Importance.High

    @Test
    fun `the door's rhythm reaches the light exactly, and it ends dark`() = runTest {
        val torch = FakeTorch(this)
        val runner = TorchRunner(torch, backgroundScope)
        runner.run(TorchFlash.pattern(high, reduceMotion = false)!!.take(2), high)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(0L to true, 200L to false, 500L to true, 700L to false, 2_500L to false), torch.calls)
        assertFalse(torch.isLit)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `cancelled while lit, the light still goes off`() = runTest {
        val torch = FakeTorch(this)
        val session = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val runner = TorchRunner(torch, session)
        runner.run(TorchFlash.pattern(critical, reduceMotion = false)!!, critical)
        runCurrent()
        advanceTimeBy(300)
        assertTrue(torch.isLit)
        session.cancel()
        runCurrent()
        assertEquals(300L to false, torch.calls.last())
        assertFalse(torch.isLit)
    }

    @Test
    fun `stop in the middle of a flash turns the light off and nothing follows`() = runTest {
        val torch = FakeTorch(this)
        val runner = TorchRunner(torch, backgroundScope)
        runner.run(TorchFlash.pattern(critical, reduceMotion = false)!!, critical)
        runCurrent()
        advanceTimeBy(1_500)
        assertTrue(torch.isLit)
        runner.stop()
        runCurrent()
        val count = torch.calls.size
        assertEquals(1_500L to false, torch.calls.last())
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(count, torch.calls.size)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a light that refuses is not asked again`() = runTest {
        val torch = FakeTorch(this, refuses = true)
        var refused = 0
        val runner = TorchRunner(torch, backgroundScope)
        runner.run(TorchFlash.pattern(critical, reduceMotion = false)!!, critical) { refused += 1 }
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, torch.calls.count { it.second })
        assertEquals(1, refused)
        assertFalse(torch.isLit)
    }

    @Test
    fun `a door during an emergency leaves it blinking, an emergency during the door takes over`() = runTest {
        val torch = FakeTorch(this)
        val runner = TorchRunner(torch, backgroundScope)
        runner.run(TorchFlash.pattern(critical, reduceMotion = false)!!, critical)
        runCurrent()
        advanceTimeBy(1_000)
        assertFalse(runner.run(TorchFlash.pattern(high, reduceMotion = false)!!, high))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(torch.calls.none { it.first in 1_001L..1_199L })
        assertEquals(listOf(0L, 1_200L), torch.calls.filter { it.second }.map { it.first }.take(2))
        runner.stop()
        runCurrent()

        torch.calls.clear()
        val start = testScheduler.currentTime
        runner.run(TorchFlash.pattern(high, reduceMotion = false)!!, high)
        runCurrent()
        advanceTimeBy(100)
        assertTrue(runner.run(TorchFlash.pattern(critical, reduceMotion = false)!!, critical))
        runCurrent()
        advanceTimeBy(800)
        runCurrent()
        val after = torch.calls.map { it.first - start to it.second }
        assertEquals(listOf(0L to true, 100L to false, 100L to true, 800L to false), after)
    }

    @Test
    fun `a rhythm that never ends is cut at fifteen seconds`() = runTest {
        val torch = FakeTorch(this)
        val runner = TorchRunner(torch, backgroundScope)
        runner.run(List(1_000) { TorchFlash.Step(1.0, 0.6) }, critical)
        runCurrent()
        advanceTimeBy(15_200)
        runCurrent()
        assertFalse(torch.isLit)
        assertEquals(15_000L to false, torch.calls.last())
        assertFalse(runner.isRunning)
        val count = torch.calls.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(count, torch.calls.size)
    }
}
