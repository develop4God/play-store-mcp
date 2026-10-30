package io.devexpert.playstore

import com.google.api.services.androidpublisher.model.TrackRelease
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackMergeTest {
    private fun release(status: String, vararg codes: Long) =
        TrackRelease().apply {
            this.status = status
            versionCodes = codes.toList()
        }

    private fun merged(existing: List<TrackRelease>, new: TrackRelease) =
        TrackMerge.releasesAfter(existing, new).map { it.status to it.versionCodes }

    @Test
    fun `a new draft keeps the live completed release`() {
        val result = merged(listOf(release("completed", 139)), release("draft", 140))

        assertEquals(listOf("completed" to listOf(139L), "draft" to listOf(140L)), result)
    }

    @Test
    fun `a new draft replaces an older draft so the track never holds two`() {
        val result = merged(listOf(release("completed", 139), release("draft", 140)), release("draft", 141))

        assertEquals(listOf("completed" to listOf(139L), "draft" to listOf(141L)), result)
    }

    @Test
    fun `a new draft keeps in progress and halted releases`() {
        val existing = listOf(release("inProgress", 138), release("halted", 137))

        val result = merged(existing, release("draft", 140))

        assertEquals(3, result.size)
    }

    @Test
    fun `a new completed release supersedes the old completed one but keeps drafts`() {
        val result = merged(listOf(release("completed", 139), release("draft", 140)), release("completed", 141))

        assertEquals(listOf("draft" to listOf(140L), "completed" to listOf(141L)), result)
    }

    @Test
    fun `a release with the same version code is replaced not duplicated`() {
        val result = merged(listOf(release("draft", 141)), release("draft", 141))

        assertEquals(listOf("draft" to listOf(141L)), result)
    }

    @Test
    fun `an empty track yields only the new release`() {
        assertEquals(listOf("draft" to listOf(141L)), merged(emptyList(), release("draft", 141)))
    }
}
