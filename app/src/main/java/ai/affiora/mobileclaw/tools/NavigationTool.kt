package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class NavigationTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "navigate"

    override val description: String =
        "Open Google Maps for navigation, place search, or viewing a location. Actions: 'directions' to start navigation, 'search_place' to search for a place, 'open_map' to view coordinates on the map."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("directions"))
                    add(JsonPrimitive("search_place"))
                    add(JsonPrimitive("open_map"))
                })
                put("description", JsonPrimitive("Action: 'directions', 'search_place', or 'open_map'."))
            })
            put("destination", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Destination address or place name (required for 'directions')."))
            })
            put("mode", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("driving"))
                    add(JsonPrimitive("walking"))
                    add(JsonPrimitive("transit"))
                    add(JsonPrimitive("bicycling"))
                })
                put("description", JsonPrimitive("Travel mode (default: driving). For 'directions' only."))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search query (required for 'search_place')."))
            })
            put("latitude", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive("Latitude (required for 'open_map')."))
            })
            put("longitude", buildJsonObject {
                put("type", JsonPrimitive("number"))
                put("description", JsonPrimitive("Longitude (required for 'open_map')."))
            })
            put("label", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Label for the map pin (optional, for 'open_map')."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        return when (action) {
            "directions" -> executeDirections(params)
            "search_place" -> executeSearchPlace(params)
            "open_map" -> executeOpenMap(params)
            else -> ToolResult.Error("Unknown action: $action. Must be 'directions', 'search_place', or 'open_map'.")
        }
    }

    private fun executeDirections(params: Map<String, JsonElement>): ToolResult {
        val destination = params["destination"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: destination")
        val mode = params["mode"]?.jsonPrimitive?.content ?: "driving"

        val modeChar = when (mode) {
            "driving" -> "d"
            "walking" -> "w"
            "transit" -> "r"
            "bicycling" -> "b"
            else -> "d"
        }

        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeChar")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Started navigation to '$destination' (mode: $mode)")
        } catch (e: Exception) {
            // Fallback: open in browser if Maps not installed
            try {
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$mode")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                ToolResult.Success("Opened navigation in browser to '$destination' (mode: $mode)")
            } catch (e2: Exception) {
                ToolResult.Error("Failed to open navigation: ${e2.message}")
            }
        }
    }

    private fun executeSearchPlace(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")

        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Searching for '$query' on Maps")
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://www.google.com/maps/search/${Uri.encode(query)}")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                ToolResult.Success("Searching for '$query' in browser Maps")
            } catch (e2: Exception) {
                ToolResult.Error("Failed to search place: ${e2.message}")
            }
        }
    }

    private fun executeOpenMap(params: Map<String, JsonElement>): ToolResult {
        val latitude = params["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: return ToolResult.Error("Missing required parameter: latitude")
        val longitude = params["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: return ToolResult.Error("Missing required parameter: longitude")
        val label = params["label"]?.jsonPrimitive?.content

        return try {
            val uriStr = if (label != null) {
                "geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})"
            } else {
                "geo:$latitude,$longitude?q=$latitude,$longitude"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val labelSuffix = if (label != null) " ($label)" else ""
            ToolResult.Success("Opened map at $latitude, $longitude$labelSuffix")
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://www.google.com/maps/@$latitude,$longitude,15z")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                ToolResult.Success("Opened map in browser at $latitude, $longitude")
            } catch (e2: Exception) {
                ToolResult.Error("Failed to open map: ${e2.message}")
            }
        }
    }
}
