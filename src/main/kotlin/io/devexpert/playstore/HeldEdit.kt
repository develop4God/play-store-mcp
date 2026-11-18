package io.devexpert.playstore

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.androidpublisher.AndroidPublisher
import org.slf4j.LoggerFactory

/** Outcome of a store-presence write. */
data class WriteResult(val success: Boolean, val editId: String?, val message: String)

private val logger = LoggerFactory.getLogger("HeldEdit")

/**
 * The only way this server commits store-presence changes. The commit is always made with
 * `changesNotSentForReview=true`, so nothing reaches Google review until a human sends it from the Play Console.
 * There is deliberately no parameter to turn that off. If [block] or the commit fails, the edit is deleted and
 * nothing changes in Play.
 */
// Error boundary: any failure must clean up the open edit and become a failed result.
@Suppress("TooGenericExceptionCaught")
internal fun AndroidPublisher.commitHeldEdit(packageName: String, block: (editId: String) -> String): WriteResult {
    var openEdit: String? = null
    var committing = false
    return try {
        val editId = edits().insert(packageName, null).execute().id
        openEdit = editId
        val summary = block(editId)
        committing = true
        edits().commit(packageName, editId).setChangesNotSentForReview(true).execute()
        openEdit = null
        WriteResult(true, editId, "$summary. Saved as a draft: it is NOT sent for review; send it for review in the Play Console.")
    } catch (e: Exception) {
        logger.error("Play write failed: {}", e.message)
        val reason = e.message ?: e.javaClass.simpleName
        // A network failure while committing leaves the outcome unknown: Play may have applied the edit.
        val outcomeUnknown = committing && e !is GoogleJsonResponseException
        if (!outcomeUnknown) openEdit?.let { deleteQuietly(packageName, it) }
        val outcome = if (outcomeUnknown) {
            "The commit may have been applied: check the Play Console (or get_app_details) before retrying."
        } else {
            "Nothing was changed in Play."
        }
        WriteResult(false, null, "Operation failed: $reason. $outcome")
    }
}

private fun AndroidPublisher.deleteQuietly(packageName: String, editId: String) {
    runCatching { edits().delete(packageName, editId).execute() }
        .onFailure { logger.warn("Could not delete the open edit {}: {}", editId, it.message) }
}
