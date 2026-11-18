package io.devexpert.playstore

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.androidpublisher.AndroidPublisher
import org.slf4j.LoggerFactory

/** Outcome of a store-presence write. */
data class WriteResult(val success: Boolean, val editId: String?, val message: String)

private val logger = LoggerFactory.getLogger("StoreEdit")

private const val ERROR_IF_IN_REVIEW = "ERROR_IF_IN_REVIEW"

/**
 * The only way this server commits store-presence changes.
 *
 * By default ([sendForReview] false) the commit is held back from Google review with `changesNotSentForReview=true`.
 * Some apps (those Google reviews automatically) reject that flag; for them the caller must opt in with
 * `sendForReview=true`, which commits without it. This is never done silently: a rejected hold is reported as an
 * error. A send never cancels a review already in progress (`ERROR_IF_IN_REVIEW`). If [block] or the commit fails,
 * the edit is deleted and nothing changes in Play.
 */
// Error boundary: any failure must clean up the open edit and become a failed result.
@Suppress("TooGenericExceptionCaught")
internal fun AndroidPublisher.commitStoreEdit(packageName: String, sendForReview: Boolean, block: (editId: String) -> String): WriteResult {
    var openEdit: String? = null
    var committing = false
    return try {
        val editId = edits().insert(packageName, null).execute().id
        openEdit = editId
        val summary = block(editId)
        committing = true
        val commit = edits().commit(packageName, editId)
        if (sendForReview) commit.set("changesInReviewBehavior", ERROR_IF_IN_REVIEW) else commit.setChangesNotSentForReview(true)
        commit.execute()
        openEdit = null
        WriteResult(true, editId, "$summary. ${if (sendForReview) SENT else HELD}")
    } catch (e: Exception) {
        logger.error("Play write failed: {}", e.message)
        val reason = e.message ?: e.javaClass.simpleName
        // A network failure while committing leaves the outcome unknown: Play may have applied the edit.
        val outcomeUnknown = committing && e !is GoogleJsonResponseException
        if (!outcomeUnknown) openEdit?.let { deleteQuietly(packageName, it) }
        val outcome = when {
            outcomeUnknown -> "The commit may have been applied: check the Play Console (or get_app_details) before retrying."
            !sendForReview && reason.contains("must not be set") ->
                "Nothing was changed in Play. This app cannot hold changes back from review; to send them to Google review, " +
                    "run again with sendForReview=true."
            else -> "Nothing was changed in Play."
        }
        WriteResult(false, null, "Operation failed: $reason. $outcome")
    }
}

private const val HELD = "Saved as a draft: it is NOT sent for review; send it for review in the Play Console."
private const val SENT = "SENT TO GOOGLE REVIEW. With managed publishing on it will not go live until you publish it in the Play Console."

private fun AndroidPublisher.deleteQuietly(packageName: String, editId: String) {
    runCatching { edits().delete(packageName, editId).execute() }
        .onFailure { logger.warn("Could not delete the open edit {}: {}", editId, it.message) }
}
