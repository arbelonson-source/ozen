package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudCoverTest {
    private fun failure(kind: EngineUnavailability.Kind) =
        PipelineFailure(PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(kind, ""))

    @Test
    fun `an engine already on the phone is never swapped`() {
        assertNull(CloudCover.phoneSettings(AppSettings.default, failure(EngineUnavailability.Kind.NoInternet)))
    }
}
