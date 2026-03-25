package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ToolResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class LocalToolAdapterTest {

    private fun createMockTool(
        name: String = "test_tool",
        description: String = "A test tool",
        result: ToolResult = ToolResult.Success("ok"),
    ): AndroidTool {
        val tool = mockk<AndroidTool>()
        io.mockk.every { tool.name } returns name
        io.mockk.every { tool.description } returns description
        io.mockk.every { tool.parameters } returns JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "query" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "description" to JsonPrimitive("Search query"),
                            ),
                        ),
                    ),
                ),
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
            ),
        )
        coEvery { tool.execute(any()) } returns result
        return tool
    }

    @Test
    fun `getToolDescriptionJsonString contains name and description`() {
        val tool = createMockTool(name = "sms", description = "Read SMS messages")
        val adapter = AndroidToolOpenApiAdapter(tool)
        val json = adapter.getToolDescriptionJsonString()

        assertThat(json).contains("\"name\":\"sms\"")
        assertThat(json).contains("\"description\":\"Read SMS messages\"")
        assertThat(json).contains("\"parameters\"")
    }

    @Test
    fun `execute returns success data`() {
        val tool = createMockTool(result = ToolResult.Success("3 unread messages"))
        val adapter = AndroidToolOpenApiAdapter(tool)
        val result = adapter.execute("""{"query": "unread"}""")

        assertThat(result).isEqualTo("3 unread messages")
    }

    @Test
    fun `execute returns error message`() {
        val tool = createMockTool(result = ToolResult.Error("Permission denied"))
        val adapter = AndroidToolOpenApiAdapter(tool)
        val result = adapter.execute("{}")

        assertThat(result).contains("Error: Permission denied")
    }

    @Test
    fun `execute handles NeedsConfirmation by declining`() {
        val tool = createMockTool(result = ToolResult.NeedsConfirmation("Send SMS?", "req-1"))
        val adapter = AndroidToolOpenApiAdapter(tool)
        val result = adapter.execute("{}")

        assertThat(result).contains("confirmation")
    }

    @Test
    fun `execute handles empty args`() {
        val tool = createMockTool(result = ToolResult.Success("done"))
        val adapter = AndroidToolOpenApiAdapter(tool)
        val result = adapter.execute("{}")

        assertThat(result).isEqualTo("done")
    }

    @Test
    fun `execute handles malformed JSON`() {
        val tool = createMockTool(result = ToolResult.Success("done"))
        val adapter = AndroidToolOpenApiAdapter(tool)
        val result = adapter.execute("not json at all")

        // Should not crash — returns result with empty params
        assertThat(result).isEqualTo("done")
    }

    @Test
    fun `createLocalToolProviders creates provider per tool`() {
        val registry = mapOf(
            "sms" to createMockTool(name = "sms"),
            "calendar" to createMockTool(name = "calendar"),
            "contacts" to createMockTool(name = "contacts"),
        )
        val providers = createLocalToolProviders(registry)

        assertThat(providers).hasSize(3)
    }
}
