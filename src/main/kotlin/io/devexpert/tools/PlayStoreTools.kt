package io.devexpert.tools

import io.devexpert.playstore.CatalogQueries
import io.devexpert.playstore.CommandResult
import io.devexpert.playstore.DeployRequest
import io.devexpert.playstore.ImageCommands
import io.devexpert.playstore.InvalidRequestException
import io.devexpert.playstore.PromoteRequest
import io.devexpert.playstore.ReleaseCommands
import io.devexpert.playstore.ReplaceStoreImagesRequest
import io.devexpert.playstore.StoreImageSlots
import io.devexpert.playstore.TrackPolicy
import io.devexpert.playstore.UpdateReleaseNotesRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
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

class PlayStoreTools(
    private val commands: ReleaseCommands,
    private val catalog: CatalogQueries,
    private val images: ImageCommands
) {
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
                commands.listReleases(packageName)
            }
        }

        registerGetAppDetails(server)
        registerReplaceStoreImages(server)
        logger.info(
            "Registered tools: deploy_app, promote_release, update_release_notes, get_releases, get_app_details, replace_store_images"
        )
    }

    private fun registerGetAppDetails(server: Server) {
        server.addTool(
            name = "get_app_details",
            description = "Read-only. Everything the Play Console shows for one language of the store listing: " +
                "name, short and full description with their length and limit, video, and the icon, feature graphic " +
                "and phone/tablet screenshots, plus the app's default language and contact details.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id, e.g. com.example.app"))
                    put("language", property("string", "Play language tag of the listing, e.g. en-US or es-419"))
                },
                required = listOf("packageName", "language"),
            ),
        ) { request ->
            handle {
                catalog.appDetails(request.requireText("packageName"), request.requireText("language"))
            }
        }
    }

    private fun registerReplaceStoreImages(server: Server) {
        server.addTool(
            name = "replace_store_images",
            description = "Replace ALL images of given types in given languages of the store listing, in ONE edit " +
                "(one commit, one review submission). For each {language, imageType} the current images are deleted and the " +
                "files uploaded in order; other languages and types are untouched. All or nothing: if any step fails, nothing " +
                "changes. By default the change is held back from review; apps that cannot hold changes back fail with a clear " +
                "error, and sendForReview=true sends them to Google review (a review in progress is never cancelled). " +
                "Runs as a dry run unless confirm=true.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("packageName", property("string", "Application id, e.g. com.example.app"))
                    put("replacements", replacementsSchema())
                    put("sendForReview", property("boolean", "Send the change to Google review instead of holding it back; default false"))
                    put("confirm", property("boolean", "Must be true to actually replace; default false (dry run)"))
                },
                required = listOf("packageName", "replacements")
            )
        ) { request ->
            handle { images.replaceImages(ReplaceStoreImagesRequest.parse(request.arguments)) }
        }
    }

    private fun replacementsSchema(): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive("One entry per language and image type; each pair may appear once"))
        put(
            "items",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put("language", property("string", "Play language tag, e.g. en-US or es-419"))
                        put("imageType", property("string", "Image type to replace", enum = StoreImageSlots.maxPerType.keys))
                        put(
                            "imagePaths",
                            buildJsonObject {
                                put("type", JsonPrimitive("array"))
                                put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                put("description", JsonPrimitive("Absolute paths of the new .png/.jpg files, in display order (icon and featureGraphic take one)"))
                            }
                        )
                    }
                )
                put("required", JsonArray(listOf("language", "imageType", "imagePaths").map { JsonPrimitive(it) }))
            }
        )
    }

    private fun CallToolRequest.requireText(key: String): String {
        val primitive = arguments[key] as? JsonPrimitive ?: throw InvalidRequestException("$key is required")
        if (!primitive.isString) throw InvalidRequestException("$key must be text")
        return primitive.content
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
