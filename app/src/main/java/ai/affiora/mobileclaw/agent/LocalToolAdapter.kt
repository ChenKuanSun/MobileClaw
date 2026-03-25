package ai.affiora.mobileclaw.agent

import android.util.Log
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapts MobileClaw's [AndroidTool] instances to LiteRT-LM's [OpenApiTool] interface.
 *
 * LiteRT-LM uses [OpenApiTool] for JSON-schema-based tool calling with constrained decoding.
 * This adapter bridges our existing tool registry (29 tools) to the on-device inference engine,
 * allowing Gemma 4 to call the same tools as cloud models.
 *
 * Each AndroidTool becomes an OpenApiTool:
 * - [getToolDescriptionJsonString] returns the OpenAPI-style tool definition
 * - [execute] deserializes the JSON args, calls [AndroidTool.execute], returns the result
 */
private const val TAG = "LocalToolAdapter"

/**
 * Wraps a single [AndroidTool] as a LiteRT-LM [OpenApiTool].
 */
class AndroidToolOpenApiAdapter(
    private val tool: AndroidTool,
) : OpenApiTool {

    private val gson = Gson()

    /**
     * Returns the tool definition as an OpenAPI-compatible JSON string.
     * Format matches what LiteRT-LM constrained decoding expects.
     */
    override fun getToolDescriptionJsonString(): String {
        val toolDef = JsonObject().apply {
            addProperty("name", tool.name)
            addProperty("description", tool.description)
            add("parameters", gson.toJsonTree(
                mapOf(
                    "type" to "object",
                    "properties" to tool.parameters["properties"]?.toString()?.let { gson.fromJson(it, Any::class.java) },
                    "required" to tool.parameters["required"]?.toString()?.let { gson.fromJson(it, Any::class.java) },
                ),
            ))
        }
        return gson.toJson(toolDef)
    }

    /**
     * Execute the tool with JSON arguments string from the model.
     * Runs the AndroidTool's suspend execute() in a blocking context
     * (LiteRT-LM tool execution is synchronous).
     */
    override fun execute(argsJson: String): String {
        return try {
            Log.i(TAG, "Executing tool '${tool.name}' with args: $argsJson")

            // Parse JSON string to Map<String, JsonElement> for AndroidTool
            val params = parseArgsToMap(argsJson)

            // AndroidTool.execute is suspend — bridge to blocking since OpenApiTool.execute is sync
            val result = runBlocking {
                tool.execute(params)
            }

            when (result) {
                is ToolResult.Success -> {
                    Log.i(TAG, "Tool '${tool.name}' succeeded: ${result.data.take(100)}")
                    result.data
                }
                is ToolResult.Error -> {
                    Log.w(TAG, "Tool '${tool.name}' error: ${result.message}")
                    "Error: ${result.message}"
                }
                is ToolResult.NeedsConfirmation -> {
                    // For local models, auto-decline dangerous actions for safety
                    Log.w(TAG, "Tool '${tool.name}' needs confirmation — declining for local model safety")
                    "Action requires user confirmation. Please ask the user to approve this action."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool '${tool.name}' execution failed: ${e.message}", e)
            "Error executing tool: ${e.message}"
        }
    }

    private fun parseArgsToMap(json: String): Map<String, JsonElement> {
        if (json.isBlank() || json == "{}") return emptyMap()

        return try {
            val gsonObj = gson.fromJson(json, com.google.gson.JsonObject::class.java)
            gsonObj.entrySet().associate { (key, value) ->
                key to gsonValueToKotlinxJson(value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool args: $json", e)
            emptyMap()
        }
    }

    /** Convert Gson JsonElement to kotlinx.serialization JsonElement. */
    private fun gsonValueToKotlinxJson(value: com.google.gson.JsonElement): JsonElement {
        return when {
            value.isJsonPrimitive -> {
                val p = value.asJsonPrimitive
                when {
                    p.isBoolean -> JsonPrimitive(p.asBoolean)
                    p.isNumber -> JsonPrimitive(p.asNumber)
                    else -> JsonPrimitive(p.asString)
                }
            }
            value.isJsonArray -> {
                kotlinx.serialization.json.JsonArray(
                    value.asJsonArray.map { gsonValueToKotlinxJson(it) },
                )
            }
            value.isJsonObject -> {
                kotlinx.serialization.json.JsonObject(
                    value.asJsonObject.entrySet().associate { (k, v) -> k to gsonValueToKotlinxJson(v) },
                )
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}

/**
 * Convert a map of [AndroidTool]s to a list of LiteRT-LM [ToolProvider]s.
 * Each tool becomes an [OpenApiTool] adapter wrapped in a [ToolProvider].
 */
fun createLocalToolProviders(toolRegistry: Map<String, AndroidTool>): List<ToolProvider> {
    return toolRegistry.values.map { androidTool ->
        tool(AndroidToolOpenApiAdapter(androidTool))
    }
}
