package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class StartCaptionsRequestTest {
    private data class Case(
        val name: String,
        val expected: Boolean,
        val action: String? = StartCaptionsRequest.ACTION,
        val newIntent: Boolean = false,
        val recreated: Boolean = false,
        val fromHistory: Boolean = false,
        val walkthroughShowing: Boolean = false,
        val crashGuardAllows: Boolean = true,
        val firstAppearancePending: Boolean = false,
    )

    @Test
    fun theStartActionStartsCaptionsExactlyWhereTheAppShouldAndNowhereElse() {
        val cases = listOf(
            Case("cold start after the app was closed", expected = true),
            Case("cold start whose first appearance will start them anyway", expected = false, firstAppearancePending = true),
            Case("app already open and brought forward", expected = true, newIntent = true),
            Case("app open, first appearance not yet taken", expected = true, newIntent = true, firstAppearancePending = true),
            Case("screen rebuilt by a turn or a language change", expected = false, recreated = true),
            Case("opened again from recent apps", expected = false, fromHistory = true),
            Case("walkthrough on screen, cold start", expected = false, walkthroughShowing = true),
            Case("walkthrough on screen, app already open", expected = false, newIntent = true, walkthroughShowing = true),
            Case("model killed the app at load, cold start", expected = false, crashGuardAllows = false),
            Case("model killed the app at load, app already open", expected = false, newIntent = true, crashGuardAllows = false),
            Case("another action", expected = false, action = "android.intent.action.MAIN"),
            Case("another action on a new intent", expected = false, newIntent = true, action = "android.intent.action.VIEW"),
            Case("no action", expected = false, action = null),
        )
        cases.forEach {
            val got = StartCaptionsRequest.shouldStart(
                it.action, it.newIntent, it.recreated, it.fromHistory, it.walkthroughShowing, it.crashGuardAllows, it.firstAppearancePending,
            )
            assertEquals(it.expected, got, it.name)
        }
    }
}
