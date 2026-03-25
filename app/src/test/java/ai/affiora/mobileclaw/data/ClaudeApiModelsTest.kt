package ai.affiora.mobileclaw.data

import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ClaudeTool
import ai.affiora.mobileclaw.data.model.ContentBlock
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ClaudeApiModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `serialize ClaudeRequest with text content`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-6",
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.Text(text = "Hello"),
                ),
            ),
            system = "You are helpful.",
            maxTokens = 1024,
            stream = false,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ClaudeRequest>(encoded)

        assertThat(decoded.model).isEqualTo("claude-sonnet-4-6")
        assertThat(decoded.messages).hasSize(1)
        assertThat(decoded.messages[0].role).isEqualTo("user")
        assertThat(decoded.system).isEqualTo("You are helpful.")
        assertThat(decoded.maxTokens).isEqualTo(1024)
        assertThat(decoded.stream).isFalse()

        val content = decoded.messages[0].content
        assertThat(content).isInstanceOf(ClaudeContent.Text::class.java)
        assertThat((content as ClaudeContent.Text).text).isEqualTo("Hello")
    }

    @Test
    fun `serialize ClaudeRequest with tool result content`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-6",
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.ToolResult(
                        toolUseId = "tool_abc123",
                        content = "screenshot taken",
                    ),
                ),
            ),
            maxTokens = 512,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ClaudeRequest>(encoded)

        val content = decoded.messages[0].content
        assertThat(content).isInstanceOf(ClaudeContent.ToolResult::class.java)
        val toolResult = content as ClaudeContent.ToolResult
        assertThat(toolResult.toolUseId).isEqualTo("tool_abc123")
        assertThat(toolResult.content).isEqualTo("screenshot taken")
    }

    @Test
    fun `serialize ClaudeRequest with content list`() {
        val blocks = listOf(
            ContentBlock.TextBlock(text = "Here is the result"),
            ContentBlock.ToolUseBlock(
                id = "tool_use_1",
                name = "screenshot",
                input = buildJsonObject { put("format", "png") },
            ),
        )

        val request = ClaudeRequest(
            model = "claude-sonnet-4-6",
            messages = listOf(
                ClaudeMessage(
                    role = "assistant",
                    content = ClaudeContent.ContentList(blocks = blocks),
                ),
            ),
            maxTokens = 2048,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ClaudeRequest>(encoded)

        val content = decoded.messages[0].content
        assertThat(content).isInstanceOf(ClaudeContent.ContentList::class.java)
        val contentList = content as ClaudeContent.ContentList
        assertThat(contentList.blocks).hasSize(2)

        val textBlock = contentList.blocks[0]
        assertThat(textBlock).isInstanceOf(ContentBlock.TextBlock::class.java)
        assertThat((textBlock as ContentBlock.TextBlock).text).isEqualTo("Here is the result")

        val toolUseBlock = contentList.blocks[1]
        assertThat(toolUseBlock).isInstanceOf(ContentBlock.ToolUseBlock::class.java)
        val toolUse = toolUseBlock as ContentBlock.ToolUseBlock
        assertThat(toolUse.id).isEqualTo("tool_use_1")
        assertThat(toolUse.name).isEqualTo("screenshot")
        assertThat(toolUse.input["format"]).isEqualTo(JsonPrimitive("png"))
    }

    @Test
    fun `serialize ClaudeRequest with tools`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The search query")
                })
            })
        }

        val request = ClaudeRequest(
            model = "claude-sonnet-4-6",
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = ClaudeContent.Text(text = "Search for cats"),
                ),
            ),
            tools = listOf(
                ClaudeTool(
                    name = "web_search",
                    description = "Search the web",
                    inputSchema = schema,
                ),
            ),
            maxTokens = 1024,
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ClaudeRequest>(encoded)

        assertThat(decoded.tools).isNotNull()
        assertThat(decoded.tools).hasSize(1)
        assertThat(decoded.tools!![0].name).isEqualTo("web_search")
        assertThat(decoded.tools!![0].description).isEqualTo("Search the web")
        assertThat(decoded.tools!![0].inputSchema).isInstanceOf(JsonObject::class.java)
    }

    @Test
    fun `deserialize ClaudeResponse with text block`() {
        val responseJson = """
            {
                "id": "msg_001",
                "model": "claude-sonnet-4-6",
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Hello there!"}
                ],
                "stop_reason": "end_turn"
            }
        """.trimIndent()

        val response = json.decodeFromString<ClaudeResponse>(responseJson)

        assertThat(response.id).isEqualTo("msg_001")
        assertThat(response.model).isEqualTo("claude-sonnet-4-6")
        assertThat(response.role).isEqualTo("assistant")
        assertThat(response.stopReason).isEqualTo("end_turn")
        assertThat(response.content).hasSize(1)

        val block = response.content[0]
        assertThat(block).isInstanceOf(ContentBlock.TextBlock::class.java)
        assertThat((block as ContentBlock.TextBlock).text).isEqualTo("Hello there!")
    }

    @Test
    fun `deserialize ClaudeResponse with tool use block`() {
        val responseJson = """
            {
                "id": "msg_002",
                "model": "claude-sonnet-4-6",
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Let me take a screenshot."},
                    {"type": "tool_use", "id": "tu_123", "name": "screenshot", "input": {"format": "png"}}
                ],
                "stop_reason": "tool_use"
            }
        """.trimIndent()

        val response = json.decodeFromString<ClaudeResponse>(responseJson)

        assertThat(response.content).hasSize(2)
        assertThat(response.stopReason).isEqualTo("tool_use")

        val textBlock = response.content[0] as ContentBlock.TextBlock
        assertThat(textBlock.text).isEqualTo("Let me take a screenshot.")

        val toolBlock = response.content[1] as ContentBlock.ToolUseBlock
        assertThat(toolBlock.id).isEqualTo("tu_123")
        assertThat(toolBlock.name).isEqualTo("screenshot")
        assertThat(toolBlock.input["format"]).isEqualTo(JsonPrimitive("png"))
    }

    @Test
    fun `deserialize ClaudeResponse with null stop reason`() {
        val responseJson = """
            {
                "id": "msg_003",
                "model": "claude-sonnet-4-6",
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Streaming..."}
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ClaudeResponse>(responseJson)

        assertThat(response.stopReason).isNull()
    }

    @Test
    fun `ClaudeRequest defaults stream to false`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-6",
            messages = emptyList(),
            maxTokens = 100,
        )

        assertThat(request.stream).isFalse()
        assertThat(request.system).isNull()
        assertThat(request.tools).isNull()
    }

    @Test
    fun `round trip ContentBlock TextBlock`() {
        val block: ContentBlock = ContentBlock.TextBlock(text = "test content")
        val encoded = json.encodeToString(ContentBlock.serializer(), block)
        val decoded = json.decodeFromString(ContentBlock.serializer(), encoded)

        assertThat(decoded).isEqualTo(block)
    }

    @Test
    fun `round trip ContentBlock ToolUseBlock`() {
        val block: ContentBlock = ContentBlock.ToolUseBlock(
            id = "tu_456",
            name = "tap",
            input = buildJsonObject {
                put("x", 100)
                put("y", 200)
            },
        )
        val encoded = json.encodeToString(ContentBlock.serializer(), block)
        val decoded = json.decodeFromString(ContentBlock.serializer(), encoded)

        assertThat(decoded).isEqualTo(block)
    }

    @Test
    fun `serialize ContentBlock ToolResultBlock`() {
        val block: ContentBlock = ContentBlock.ToolResultBlock(
            toolUseId = "tu_789",
            content = "the tool output",
        )
        val encoded = json.encodeToString(ContentBlock.serializer(), block)

        assertThat(encoded).contains("\"type\":\"tool_result\"")
        assertThat(encoded).contains("\"tool_use_id\":\"tu_789\"")
        assertThat(encoded).contains("\"content\":\"the tool output\"")
    }

    @Test
    fun `deserialize ContentBlock ToolResultBlock from JSON`() {
        val blockJson = """
            {"type": "tool_result", "tool_use_id": "tu_abc", "content": "screenshot captured successfully"}
        """.trimIndent()

        val decoded = json.decodeFromString(ContentBlock.serializer(), blockJson)

        assertThat(decoded).isInstanceOf(ContentBlock.ToolResultBlock::class.java)
        val toolResult = decoded as ContentBlock.ToolResultBlock
        assertThat(toolResult.toolUseId).isEqualTo("tu_abc")
        assertThat(toolResult.content).isEqualTo("screenshot captured successfully")
    }

    @Test
    fun `round trip ContentBlock ToolResultBlock`() {
        val block: ContentBlock = ContentBlock.ToolResultBlock(
            toolUseId = "tu_round",
            content = "result data here",
        )
        val encoded = json.encodeToString(ContentBlock.serializer(), block)
        val decoded = json.decodeFromString(ContentBlock.serializer(), encoded)

        assertThat(decoded).isEqualTo(block)
    }

    @Test
    fun `round trip ContentList with TextBlock, ToolUseBlock, and ToolResultBlock`() {
        val blocks = listOf(
            ContentBlock.TextBlock(text = "I will use a tool now."),
            ContentBlock.ToolUseBlock(
                id = "tu_mix_1",
                name = "screenshot",
                input = buildJsonObject { put("delay", 500) },
            ),
            ContentBlock.ToolResultBlock(
                toolUseId = "tu_mix_1",
                content = "screenshot saved to /tmp/screen.png",
            ),
        )

        val contentList = ClaudeContent.ContentList(blocks = blocks)
        val encoded = json.encodeToString(ClaudeContent.serializer(), contentList)
        val decoded = json.decodeFromString(ClaudeContent.serializer(), encoded)

        assertThat(decoded).isInstanceOf(ClaudeContent.ContentList::class.java)
        val decodedList = decoded as ClaudeContent.ContentList
        assertThat(decodedList.blocks).hasSize(3)

        assertThat(decodedList.blocks[0]).isInstanceOf(ContentBlock.TextBlock::class.java)
        assertThat((decodedList.blocks[0] as ContentBlock.TextBlock).text)
            .isEqualTo("I will use a tool now.")

        assertThat(decodedList.blocks[1]).isInstanceOf(ContentBlock.ToolUseBlock::class.java)
        val toolUse = decodedList.blocks[1] as ContentBlock.ToolUseBlock
        assertThat(toolUse.id).isEqualTo("tu_mix_1")
        assertThat(toolUse.name).isEqualTo("screenshot")

        assertThat(decodedList.blocks[2]).isInstanceOf(ContentBlock.ToolResultBlock::class.java)
        val toolResult = decodedList.blocks[2] as ContentBlock.ToolResultBlock
        assertThat(toolResult.toolUseId).isEqualTo("tu_mix_1")
        assertThat(toolResult.content).isEqualTo("screenshot saved to /tmp/screen.png")
    }
}
