package io.devexpert.playstore

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.api.services.androidpublisher.model.LocalizedText
import com.google.api.services.androidpublisher.model.Track
import com.google.api.services.androidpublisher.model.TrackRelease
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.ServiceAccountCredentials
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream

/**
 * Talks to the Google Play Android Publisher API. The only network peer is Google.
 */
class PlayStoreClient(private val serviceAccountKeyPath: String) : PlayPublisher {
    private val logger = LoggerFactory.getLogger(PlayStoreClient::class.java)

    private val publisher: AndroidPublisher by lazy { initializePublisher() }

    private fun initializePublisher(): AndroidPublisher {
        val keyFile = File(serviceAccountKeyPath)
        if (!keyFile.isFile) throw PlayStoreException("Service account key file not found")

        return try {
            val credential = FileInputStream(keyFile).use { stream ->
                ServiceAccountCredentials.fromStream(stream)
                    .createScoped(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
            }
            val credentialsAdapter = HttpCredentialsAdapter(credential)
            val requestInitializer = HttpRequestInitializer { request ->
                credentialsAdapter.initialize(request)
                request.connectTimeout = CONNECT_TIMEOUT_MS
                request.readTimeout = UPLOAD_READ_TIMEOUT_MS
            }
            AndroidPublisher.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                requestInitializer
            ).setApplicationName(APPLICATION_NAME).build()
        } catch (e: Exception) {
            throw PlayStoreException("Failed to initialize API client: ${e.message}", e)
        }
    }

    override fun listReleases(packageName: String): List<PlayStoreRelease> {
        val edits = publisher.edits()
        val editId = edits.insert(packageName, null).execute().id
        try {
            val tracks = edits.tracks().list(packageName, editId).execute().tracks.orEmpty()
            return tracks.flatMap { track ->
                track.releases.orEmpty().map { release ->
                    PlayStoreRelease(
                        track = track.track ?: "unknown",
                        status = release.status ?: "unknown",
                        versionCodes = release.versionCodes.orEmpty(),
                        userFraction = release.userFraction,
                        name = release.name
                    )
                }
            }
        } finally {
            runCatching { edits.delete(packageName, editId).execute() }
        }
    }

    override fun deploy(request: DeployRequest): PlayStoreDeploymentResult {
        logger.info("Deploying to the {} track of {}", request.track, request.packageName)
        return runEdit(request.packageName, request.track) { editId ->
            val versionCode = upload(request, editId)
            val release = TrackRelease().apply {
                name = "Release $versionCode"
                versionCodes = listOf(versionCode)
                status = TrackPolicy.releaseStatusFor(request.track)
                if (request.releaseNotes.isNotEmpty()) {
                    releaseNotes = request.releaseNotes.map { (language, text) ->
                        LocalizedText().apply {
                            this.language = language
                            this.text = text
                        }
                    }
                }
            }
            setTrack(request.packageName, editId, request.track, release)
            versionCode to if (TrackPolicy.holdsForReview(request.track)) {
                "Uploaded and saved as a draft on ${request.track}"
            } else {
                "Uploaded and released to ${request.track}"
            }
        }
    }

    override fun promote(request: PromoteRequest): PlayStoreDeploymentResult {
        logger.info("Promoting from {} to {} for {}", request.fromTrack, request.toTrack, request.packageName)
        return runEdit(request.packageName, request.toTrack) { editId ->
            val source = publisher.edits().tracks().get(request.packageName, editId, request.fromTrack).execute()
            val sourceRelease = source.releases.orEmpty().find { it.versionCodes.orEmpty().contains(request.versionCode) }
                ?: throw PlayStoreException("Version ${request.versionCode} not found in ${request.fromTrack}")
            if (sourceRelease.status != "completed") {
                throw PlayStoreException(
                    "Only completed releases can be promoted; ${request.versionCode} is ${sourceRelease.status}"
                )
            }
            val release = TrackRelease().apply {
                name = sourceRelease.name
                versionCodes = sourceRelease.versionCodes
                status = "completed"
                releaseNotes = sourceRelease.releaseNotes
            }
            setTrack(request.packageName, editId, request.toTrack, release)
            request.versionCode to "Promoted from ${request.fromTrack} to ${request.toTrack}"
        }
    }

    override fun updateReleaseNotes(request: UpdateReleaseNotesRequest): PlayStoreDeploymentResult {
        logger.info("Updating release notes on the {} track of {}", request.track, request.packageName)
        return runEdit(request.packageName, request.track) { editId ->
            val track = try {
                publisher.edits().tracks().get(request.packageName, editId, request.track).execute()
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == HTTP_NOT_FOUND) throw PlayStoreException("Track ${request.track} has no releases") else throw e
            }
            val releases = track.releases.orEmpty()
            val matches = releases.filter { it.versionCodes.orEmpty().contains(request.versionCode) }
            val target = matches.find { it.status == "draft" } ?: matches.firstOrNull()
                ?: throw PlayStoreException("Version ${request.versionCode} not found in ${request.track}")
            if (TrackPolicy.holdsForReview(request.track) && target.status != "draft") {
                throw PlayStoreException(
                    "Only draft releases can be edited on ${request.track}; ${request.versionCode} is ${target.status}"
                )
            }
            target.releaseNotes = request.releaseNotes.map { (language, text) ->
                LocalizedText().apply {
                    this.language = language
                    this.text = text
                }
            }
            publisher.edits().tracks().update(request.packageName, editId, request.track, track.setReleases(releases)).execute()
            request.versionCode to "Release notes updated on ${request.track}"
        }
    }

    /** Uploads the bundle or APK and returns the version code Play assigned from the file itself. */
    private fun upload(request: DeployRequest, editId: String): Long {
        val file = File(request.apkPath)
        val edits = publisher.edits()
        return if (file.extension.equals("aab", ignoreCase = true)) {
            edits.bundles().upload(request.packageName, editId, FileContent(MIME_BINARY, file)).execute().versionCode
        } else {
            edits.apks().upload(request.packageName, editId, FileContent(MIME_APK, file)).execute().versionCode
        }.toLong()
    }

    /**
     * Sets [release] on the track without dropping other work in progress; see [TrackMerge].
     */
    private fun setTrack(packageName: String, editId: String, trackName: String, release: TrackRelease) {
        val existing = try {
            publisher.edits().tracks().get(packageName, editId, trackName).execute().releases.orEmpty()
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == HTTP_NOT_FOUND) emptyList() else throw e
        }
        val track = Track().apply {
            track = trackName
            releases = TrackMerge.releasesAfter(existing, release)
        }
        publisher.edits().tracks().update(packageName, editId, trackName, track).execute()
    }

    /** Opens an edit, runs [block], commits it, and deletes the edit if anything fails before commit. */
    private fun runEdit(
        packageName: String,
        track: String,
        block: (editId: String) -> Pair<Long, String>
    ): PlayStoreDeploymentResult {
        var editId: String? = null
        return try {
            val id = publisher.edits().insert(packageName, null).execute().id
            editId = id
            val (versionCode, message) = block(id)
            publisher.edits().commit(packageName, id)
                .setChangesNotSentForReview(TrackPolicy.holdsForReview(track))
                .execute()
            editId = null
            val held = if (TrackPolicy.holdsForReview(track)) {
                " It is NOT sent for review: send it for review in the Play Console."
            } else {
                ""
            }
            PlayStoreDeploymentResult(true, id, packageName, track, versionCode, message + held)
        } catch (e: Exception) {
            logger.error("Play operation failed: {}", e.message)
            editId?.let { runCatching { publisher.edits().delete(packageName, it).execute() } }
            PlayStoreDeploymentResult(false, null, packageName, track, null, "Operation failed: ${e.message}")
        }
    }

    private companion object {
        const val APPLICATION_NAME = "Play Store MCP Server"
        const val HTTP_NOT_FOUND = 404
        const val CONNECT_TIMEOUT_MS = 60_000
        const val UPLOAD_READ_TIMEOUT_MS = 600_000
        const val MIME_BINARY = "application/octet-stream"
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}

/**
 * Exception thrown by Play Store operations
 */
class PlayStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
