package com.arbelonson.ozen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleTickerTest {
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun ticksOnlyWhileStartedAndTicksAtOnceOnReturn() = runTest(StandardTestDispatcher().also { Dispatchers.setMain(it) }) {
        var ticks = 0
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        val job = launch { owner.lifecycle.tickWhileStarted(1_000) { ticks++ } }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, ticks)

        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(2_500)
        assertEquals(3, ticks)

        owner.lifecycle.currentState = Lifecycle.State.CREATED
        runCurrent()
        val whileHidden = ticks
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(whileHidden, ticks)

        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(whileHidden + 1, ticks)
        job.cancel()
    }
}
