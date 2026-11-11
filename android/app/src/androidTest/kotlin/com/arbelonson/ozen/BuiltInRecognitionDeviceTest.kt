package com.arbelonson.ozen

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltInRecognitionDeviceTest {
    @Test
    fun askingAndroidEndsInAnAnswerNotAWaitOrACrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BuiltInRecognition.check(context, listOf("he", "en"))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && BuiltInRecognition.answer.let { it == "-" || it.endsWith("checking languages") }) Thread.sleep(100)
        val answer = BuiltInRecognition.answer
        Log.i("BuiltInRecognition", answer)
        assertTrue(answer, answer.startsWith("on device ") || answer == "none before Android 12")
        assertTrue(answer, !answer.endsWith("checking languages"))
    }

    @Test
    fun askedOverAndOverAndroidGivesTheSameAnswerEveryTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = BuiltInRecognition.ask(context, listOf("he", "en"))
        val answers = List(ASKED_TIMES) { BuiltInRecognition.ask(context, listOf("he", "en")) }
        assertEquals(List(ASKED_TIMES) { first }, answers)
    }

    private companion object {
        const val ASKED_TIMES = 80
    }
}
