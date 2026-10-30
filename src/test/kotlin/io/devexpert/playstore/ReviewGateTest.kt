package io.devexpert.playstore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewGateTest {
    @Test
    fun `production releases are saved as drafts and held back from review`() {
        assertEquals("draft", TrackPolicy.releaseStatusFor("production"))
        assertTrue(TrackPolicy.holdsForReview("production"))
    }

    @Test
    fun `testing tracks keep completed releases and normal commits`() {
        listOf("internal", "alpha", "beta").forEach { track ->
            assertEquals("completed", TrackPolicy.releaseStatusFor(track))
            assertFalse(TrackPolicy.holdsForReview(track))
        }
    }

    @Test
    fun `production can never be a promotion source or target`() {
        TrackPolicy.allowedPromotions.forEach { (from, to) ->
            assertFalse(from == "production" || to == "production")
        }
    }
}
