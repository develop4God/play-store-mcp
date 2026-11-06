package io.devexpert.playstore

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.androidpublisher.AndroidPublisher
import java.net.URI

/**
 * An in-process stand-in for the Play Android Publisher REST API, plugged in as the HTTP transport.
 * It records every request and answers from [tracks]; [fail] forces an HTTP error for chosen calls.
 */
internal class FakePlayApi {
    data class Recorded(val method: String, val url: String, val body: String)

    val requests = mutableListOf<Recorded>()

    /** Track name -> JSON body returned by GET .../tracks/{name}; a missing key answers 404. */
    val tracks = mutableMapOf<String, String>()

    var uploadedVersionCode = 141

    /** Returns an HTTP status to force for a request, or null to let it succeed. */
    var fail: (method: String, url: String) -> Int? = { _, _ -> null }

    val publisher: AndroidPublisher = AndroidPublisher.Builder(
        object : MockHttpTransport() {
            override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    val body = if (streamingContent != null && !url.contains("upload_id=")) contentAsString else ""
                    requests += Recorded(method, url, body)
                    return respond(method, url)
                }
            }
        },
        GsonFactory.getDefaultInstance(),
        null,
    ).setApplicationName("test").build()

    fun requestsTo(method: String, urlPart: String) = requests.filter { it.method == method && it.url.contains(urlPart) }

    // A flat routing table of fake Play endpoints; splitting it would hide the routes, not simplify them.
    @Suppress("CyclomaticComplexMethod")
    private fun respond(method: String, url: String): MockLowLevelHttpResponse {
        fail(method, url)?.let { return error(it) }
        val path = URI(url).path
        return when {
            url.contains("upload_id=") -> json("""{"versionCode": $uploadedVersionCode}""")
            url.contains("uploadType=resumable") ->
                MockLowLevelHttpResponse().setStatusCode(200).addHeader("Location", "$url&upload_id=1")
            method == "POST" && path.endsWith("/edits") -> json("""{"id": "edit-1"}""")
            method == "POST" && path.endsWith(":commit") -> json("""{"id": "edit-1"}""")
            method == "DELETE" -> MockLowLevelHttpResponse().setStatusCode(204)
            method == "GET" && path.endsWith("/tracks") ->
                json("""{"tracks": [${tracks.values.joinToString(",")}]}""")
            method == "GET" && path.contains("/tracks/") ->
                tracks[path.substringAfterLast("/tracks/")]?.let { json(it) } ?: error(404)
            method == "PUT" && path.contains("/tracks/") -> json("""{}""")
            else -> error(500)
        }
    }

    private fun json(body: String) = MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json").setContent(body)

    private fun error(status: Int) = MockLowLevelHttpResponse().setStatusCode(status)
        .setContentType("application/json")
        .setContent("""{"error": {"code": $status, "message": "fake error $status"}}""")
}
