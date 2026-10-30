package io.devexpert.playstore

import com.google.api.services.androidpublisher.model.TrackRelease

/**
 * Decides which releases a track keeps when [new] is written. Pure, so it is tested without the Play API.
 */
object TrackMerge {
    /**
     * A new draft replaces any older draft (Play allows one per track) and keeps every live, staged and
     * halted release. A new completed release supersedes the previous completed one and keeps drafts and
     * the rest. A release sharing a version code with [new] is always replaced.
     */
    fun releasesAfter(existing: List<TrackRelease>, new: TrackRelease): List<TrackRelease> {
        val superseded = if (new.status == "draft") "draft" else "completed"
        val kept = existing.filter { other ->
            other.status != superseded && other.versionCodes.orEmpty().none { it in new.versionCodes.orEmpty() }
        }
        return kept + new
    }
}
