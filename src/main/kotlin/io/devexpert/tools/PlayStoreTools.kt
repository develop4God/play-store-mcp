package io.devexpert.tools

import io.devexpert.playstore.CommandResult
import io.devexpert.playstore.DeployRequest
import io.devexpert.playstore.InvalidRequestException
import io.devexpert.playstore.PromoteRequest
import io.devexpert.playstore.ReleaseCommands
import io.devexpert.playstore.TrackPolicy
import io.devexpert.playstore.UpdateReleaseNotesRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

class PlayStoreTools(private val commands: ReleaseCommands) {
    private val logger = LoggerFactory.getLogger(PlayStoreTools::class.java)

    fun registerTools(server: Server) {
        server.addTool(
            name = "deploy_app",
            description = "Upload an AAB/APK to a testing track (internal, alpha, beta or production). On production the release is only saved as a draft and held back from review. " +
                "Runs as a dry run unless confirm=true. The version code is read from the file.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id, e.g. com.example.app"))
                    put("track", property("string", "Track; production is draft-only and never sent for review", enum = TrackPolicy.deployableTracks))
                    put("apkPath", property("string", "Absolute path to the .aab or .apk file"))
                    put("releaseNotes", property("object", "Release notes as {\"en-US\": \"text\", \"es-ES\": \"texto\"}"))
                    put("confirm", property("boolean", "Must be true to actually upload; default false (dry run)"))
                },
                required = listOf("packageName", "track", "apkPath")
            )
        ) { request ->
            handle { commands.deploy(DeployRequest.parse(request.arguments)) }
        }

        server.addTool(
            name = "promote_release",
            description = "Promote a completed release internal -> alpha, internal -> beta or alpha -> beta. " +
                "Runs as a dry run unless confirm=true.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id"))
                    put("fromTrack", property("string", "Source track", enum = TrackPolicy.promotionSources))
                    put("toTrack", property("string", "Target track", enum = TrackPolicy.promotionTargets))
                    put("versionCode", property("integer", "Version code of the release to promote"))
                    put("confirm", property("boolean", "Must be true to actually promote; default false (dry run)"))
                },
                required = listOf("packageName", "fromTrack", "toTrack", "versionCode")
            )
        ) { request ->
            handle { commands.promote(PromoteRequest.parse(request.arguments)) }
        }

        server.addTool(
            name = "update_release_notes",
            description = "Replace the release notes of an existing release (production: drafts only, never sent for review). " +
                "Runs as a dry run unless confirm=true.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id"))
                    put("track", property("string", "Track; production is draft-only and never sent for review", enum = TrackPolicy.notesEditableTracks))
                    put("versionCode", property("integer", "Version code of the release to edit"))
                    put("releaseNotes", property("object", "Release notes as {\"en-US\": \"text\", \"es-ES\": \"texto\"}"))
                    put("confirm", property("boolean", "Must be true to actually update; default false (dry run)"))
                },
                required = listOf("packageName", "track", "versionCode", "releaseNotes")
            )
        ) { request ->
            handle { commands.updateReleaseNotes(UpdateReleaseNotesRequest.parse(request.arguments)) }
        }

        server.addTool(
            name = "get_releases",
            description = "List the releases on every track of an app (read-only).",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id, e.g. com.example.app"))
                },
                required = listOf("packageName")
            )
        ) { request ->
            handle {
                val packageName = (request.arguments["packageName"] as? JsonPrimitive)?.content
                    ?: throw InvalidRequestException("packageName is required")
                CommandResult(executed = true, success = true, message = commands.listReleases(packageName))
            }
        }

        logger.info("Registered tools: deploy_app, promote_release, update_release_notes, get_releases")
    }

    /** Runs [block] off the main thread and maps validation and API failures to MCP error results. */
    private suspend fun handle(block: () -> CommandResult): CallToolResult {
        return try {
            val result = withContext(Dispatchers.IO) { block() }
            CallToolResult(content = listOf(TextContent(text = result.message)), isError = !result.success)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidRequestException) {
            CallToolResult(content = listOf(TextContent(text = "Invalid request: ${e.message}")), isError = true)
        } catch (e: Exception) {
            logger.error("Tool call failed: {}", e.javaClass.simpleName)
            CallToolResult(content = listOf(TextContent(text = "Error: ${e.message}")), isError = true)
        }
    }

    private fun property(type: String, description: String, enum: Set<String>? = null): JsonElement =
        JsonObject(
            buildMap {
                put("type", JsonPrimitive(type))
                put("description", JsonPrimitive(description))
                enum?.let { values -> put("enum", JsonArray(values.map { JsonPrimitive(it) })) }
            }
        )
}
