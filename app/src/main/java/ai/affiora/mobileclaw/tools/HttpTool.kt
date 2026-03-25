package ai.affiora.mobileclaw.tools

import android.content.Context
import ai.affiora.mobileclaw.connectors.ConnectorManager
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

class HttpTool(
    private val context: Context,
    private val httpClient: HttpClient,
    private val connectorManager: ConnectorManager? = null,
) : AndroidTool {

    override val name: String = "http"

    override val description: String =
        "Make HTTP requests to external APIs. Actions: 'request' to send a GET/POST/PUT/DELETE request. Returns status code, headers, and response body (truncated to 8KB). GET requests are auto-approved; POST/PUT/DELETE require confirmation."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("url"))
        })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray { add(JsonPrimitive("request")) })
                put("description", JsonPrimitive("The action to perform: 'request'"))
            })
            put("url", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("The URL to send the request to (required)."))
            })
            put("method", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("GET"))
                    add(JsonPrimitive("POST"))
                    add(JsonPrimitive("PUT"))
                    add(JsonPrimitive("DELETE"))
                })
                put("description", JsonPrimitive("HTTP method (default: GET)."))
            })
            put("headers", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("Request headers as a JSON object (optional)."))
            })
            put("body", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Request body string (optional, for POST/PUT)."))
            })
            put("content_type", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Content-Type header (default: application/json)."))
            })
        })
    }

    private val blockedHostPatterns = listOf(
        Regex("^localhost$", RegexOption.IGNORE_CASE),
        Regex("^127\\."),                    // 127.0.0.0/8
        Regex("^10\\."),                     // 10.0.0.0/8
        Regex("^192\\.168\\."),              // 192.168.0.0/16
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\."), // 172.16.0.0/12
        Regex("^169\\.254\\."),              // link-local
        Regex("^0\\.0\\.0\\.0$"),            // wildcard
        Regex("^\\[?::1]?$"),               // IPv6 loopback
    )

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")

        if (action != "request") {
            return ToolResult.Error("Unknown action: $action. Must be 'request'.")
        }

        val url = params["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: url")

        // SSRF protection: block internal network addresses
        val host = try {
            URI(url).host ?: return ToolResult.Error("Invalid URL: cannot parse host")
        } catch (e: Exception) {
            return ToolResult.Error("Invalid URL: ${e.message}")
        }

        if (blockedHostPatterns.any { it.containsMatchIn(host) }) {
            return ToolResult.Error("Blocked: requests to internal network addresses are not allowed ($host)")
        }

        val method = (params["method"]?.jsonPrimitive?.content ?: "GET").uppercase()
        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true

        // POST/PUT/DELETE require confirmation
        if (method != "GET" && !confirmed) {
            val bodyPreview = params["body"]?.jsonPrimitive?.content?.take(200) ?: "(no body)"
            return ToolResult.NeedsConfirmation(
                preview = "$method $url\nBody: $bodyPreview",
                requestId = "http_${method}_${UUID.randomUUID()}",
            )
        }

        return withContext(Dispatchers.IO) {
            executeRequest(url, method, params)
        }
    }

    private suspend fun executeRequest(
        url: String,
        method: String,
        params: Map<String, JsonElement>,
    ): ToolResult {
        val body = params["body"]?.jsonPrimitive?.content
        val contentTypeStr = params["content_type"]?.jsonPrimitive?.content ?: "application/json"
        val headersObj = params["headers"] as? JsonObject

        return try {
            val response = httpClient.request(url) {
                this.method = when (method) {
                    "GET" -> HttpMethod.Get
                    "POST" -> HttpMethod.Post
                    "PUT" -> HttpMethod.Put
                    "DELETE" -> HttpMethod.Delete
                    else -> return ToolResult.Error("Unsupported method: $method")
                }

                // Parse and apply content type
                val ctParts = contentTypeStr.split("/")
                if (ctParts.size == 2) {
                    contentType(ContentType(ctParts[0], ctParts[1]))
                }

                // Auto-inject connector token if available
                val connectorAuth = connectorManager?.getAuthHeaderForUrl(url)

                // Apply custom headers
                headers {
                    if (headersObj != null) {
                        for ((key, value) in headersObj) {
                            append(key, value.jsonPrimitive.content)
                        }
                    }
                    // Inject connector auth only if not already set by caller
                    if (connectorAuth != null && headersObj?.containsKey(connectorAuth.first) != true) {
                        append(connectorAuth.first, connectorAuth.second)
                    }
                }

                if (body != null) {
                    setBody(body)
                }
            }

            val responseBody = response.bodyAsText()
            val truncated = if (responseBody.length > 8192) {
                responseBody.take(8192) + "\n...[truncated]"
            } else {
                responseBody
            }

            val result = buildJsonObject {
                put("status", JsonPrimitive(response.status.value))
                put("status_description", JsonPrimitive(response.status.description))
                put("content_type", JsonPrimitive(
                    response.headers["Content-Type"] ?: "unknown"
                ))
                put("body", JsonPrimitive(truncated))
            }

            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("HTTP request failed: ${e.message}")
        }
    }
}
