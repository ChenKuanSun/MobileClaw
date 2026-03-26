package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class WebBrowserTool(
    private val context: Context,
    private val httpClient: HttpClient,
) : AndroidTool {

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

    override val name: String = "web"

    override val description: String =
        "Search the web, fetch and read web pages, or open URLs in a browser. Actions: 'search' to query DuckDuckGo, 'open_url' to fetch and extract text from a page, 'browser_open' to open a URL in the default browser. Note: browser_open opens the URL and returns immediately. Use the 'ui' tool to interact with the browser page afterward."

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("open_url"))
                    add(JsonPrimitive("browser_open"))
                })
                put("description", JsonPrimitive("The action to perform: 'search', 'open_url', or 'browser_open'"))
            })
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Search query string (required for 'search' action)."))
            })
            put("url", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("URL to fetch or open (required for 'open_url' and 'browser_open' actions)."))
            })
        })
    }

    override suspend fun execute(params: Map<String, JsonElement>): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: action")
        val confirmed = params["__confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true

        return withContext(Dispatchers.IO) {
            when (action) {
                "search" -> executeSearch(params)
                "open_url" -> {
                    executeOpenUrl(params)
                }
                "browser_open" -> executeBrowserOpen(params)
                else -> ToolResult.Error("Unknown action: $action. Must be 'search', 'open_url', or 'browser_open'.")
            }
        }
    }

    private suspend fun executeSearch(params: Map<String, JsonElement>): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: query")

        return try {
            val response = httpClient.get("https://api.duckduckgo.com/") {
                parameter("q", query)
                parameter("format", "json")
                parameter("no_html", "1")
                parameter("skip_disambig", "1")
            }

            val body = response.bodyAsText()
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val parsed = json.parseToJsonElement(body)

            val results = buildJsonObject {
                put("query", JsonPrimitive(query))

                // Extract abstract (instant answer)
                val obj = parsed as? JsonObject
                val abstract = obj?.get("Abstract")?.jsonPrimitive?.content ?: ""
                val abstractUrl = obj?.get("AbstractURL")?.jsonPrimitive?.content ?: ""
                val heading = obj?.get("Heading")?.jsonPrimitive?.content ?: ""

                if (abstract.isNotBlank()) {
                    put("answer", JsonPrimitive(abstract))
                    put("source_url", JsonPrimitive(abstractUrl))
                    put("heading", JsonPrimitive(heading))
                }

                // Extract related topics
                val relatedTopics = obj?.get("RelatedTopics")
                if (relatedTopics is kotlinx.serialization.json.JsonArray) {
                    put("results", buildJsonArray {
                        var count = 0
                        for (topic in relatedTopics) {
                            if (count >= 10) break
                            val topicObj = topic as? JsonObject ?: continue
                            val text = topicObj["Text"]?.jsonPrimitive?.content ?: continue
                            val firstUrl = topicObj["FirstURL"]?.jsonPrimitive?.content ?: ""
                            add(buildJsonObject {
                                put("text", JsonPrimitive(text))
                                put("url", JsonPrimitive(firstUrl))
                            })
                            count++
                        }
                    })
                }
            }

            ToolResult.Success(results.toString())
        } catch (e: Exception) {
            ToolResult.Error("Search failed: ${e.message}")
        }
    }

    private suspend fun executeOpenUrl(params: Map<String, JsonElement>): ToolResult {
        val url = params["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: url")

        // SSRF protection: block internal network addresses
        val host = try {
            java.net.URI(url).host ?: return ToolResult.Error("Invalid URL: cannot parse host")
        } catch (e: Exception) {
            return ToolResult.Error("Invalid URL: ${e.message}")
        }
        if (blockedHostPatterns.any { it.containsMatchIn(host) }) {
            return ToolResult.Error("Blocked: requests to internal network addresses are not allowed ($host)")
        }

        return try {
            val response = httpClient.get(url)
            val html = response.bodyAsText()
            val text = stripHtml(html)
            val truncated = if (text.length > 4000) text.take(4000) + "\n...[truncated]" else text

            val result = buildJsonObject {
                put("url", JsonPrimitive(url))
                put("content", JsonPrimitive(truncated))
                put("length", JsonPrimitive(text.length))
            }

            ToolResult.Success(result.toString())
        } catch (e: Exception) {
            ToolResult.Error("Failed to fetch URL: ${e.message}")
        }
    }

    private fun executeBrowserOpen(params: Map<String, JsonElement>): ToolResult {
        val url = params["url"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: url")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened $url in browser.")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open browser: ${e.message}")
        }
    }

    /**
     * Strips HTML tags and extracts readable text content.
     * Removes script/style blocks, then strips remaining tags,
     * decodes common HTML entities, and collapses whitespace.
     */
    private fun stripHtml(html: String): String {
        // Remove script and style blocks entirely
        var text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        // Remove HTML comments
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        // Replace block-level tags with newlines for readability
        text = text.replace(Regex("</(p|div|h[1-6]|li|tr|br|hr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // Strip all remaining tags
        text = text.replace(Regex("<[^>]+>"), " ")
        // Decode common HTML entities
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        // Decode numeric entities
        text = text.replace(Regex("&#(\\d+);")) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null) code.toChar().toString() else match.value
        }
        // Collapse whitespace
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }
}
