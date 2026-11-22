package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedOnceTest {
    @Test
    fun `asked twice before it runs, the work runs once, and asked again once it ran it runs again`() {
        val scope = TestScope(StandardTestDispatcher())
        var runs = 0
        val restart = QueuedOnce(scope) { runs += 1 }

        restart.request()
        restart.request()
        scope.runCurrent()
        assertEquals(1, runs)

        restart.request()
        scope.runCurrent()
        assertEquals(2, runs)
    }
}
