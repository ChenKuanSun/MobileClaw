package ai.affiora.mobileclaw.tools

import android.content.Context
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private lateinit var tools: Map<String, AndroidTool>
    private val mockContext: Context = mockk(relaxed = true)
    private val mockNotificationCache: NotificationCache = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        val smsTool = SmsTool(mockContext)
        val callLogTool = CallLogTool(mockContext)
        val contactsTool = ContactsTool(mockContext)
        val calendarTool = CalendarTool(mockContext)
        val notificationTool = NotificationTool(mockNotificationCache)

        tools = listOf<AndroidTool>(
            smsTool,
            callLogTool,
            contactsTool,
            calendarTool,
            notificationTool
        ).associateBy { it.name }
    }

    @Test
    fun `all tools have unique names`() {
        val names = tools.values.map { it.name }
        assertEquals(names.size, names.toSet().size, "Tool names must be unique")
    }

    @Test
    fun `all tools have non-empty descriptions`() {
        tools.values.forEach { tool ->
            assertTrue(tool.description.isNotBlank(), "Tool ${tool.name} must have a non-empty description")
        }
    }

    @Test
    fun `all tools have valid JSON schemas with type object`() {
        tools.values.forEach { tool ->
            val schema = tool.parameters
            assertTrue(schema.containsKey("type"), "Tool ${tool.name} schema must have 'type'")
            assertEquals(
                "object",
                (schema["type"] as JsonPrimitive).content,
                "Tool ${tool.name} schema type must be 'object'"
            )
        }
    }

    @Test
    fun `all tools have properties in their schema`() {
        tools.values.forEach { tool ->
            val schema = tool.parameters
            assertTrue(schema.containsKey("properties"), "Tool ${tool.name} schema must have 'properties'")
            val properties = schema["properties"]
            assertTrue(properties is JsonObject, "Tool ${tool.name} 'properties' must be a JsonObject")
        }
    }

    @Test
    fun `all tools have action parameter in schema`() {
        tools.values.forEach { tool ->
            val properties = tool.parameters["properties"] as JsonObject
            assertTrue(
                properties.containsKey("action"),
                "Tool ${tool.name} must have 'action' in properties"
            )
        }
    }

    @Test
    fun `registry contains expected number of tools`() {
        assertEquals(5, tools.size, "Registry should contain 5 tools")
    }

    @Test
    fun `registry contains expected tool names`() {
        val expectedNames = setOf("sms", "call_log", "contacts", "calendar", "notifications")
        assertEquals(expectedNames, tools.keys)
    }

    @Test
    fun `all schemas have required field containing action`() {
        tools.values.forEach { tool ->
            val schema = tool.parameters
            assertTrue(schema.containsKey("required"), "Tool ${tool.name} schema must have 'required'")
            val required = schema["required"]
            assertTrue(
                required is kotlinx.serialization.json.JsonArray,
                "Tool ${tool.name} 'required' must be a JsonArray"
            )
            val requiredArray = required as kotlinx.serialization.json.JsonArray
            val requiredNames = requiredArray.map { it.jsonPrimitive.content }
            assertTrue(
                requiredNames.contains("action"),
                "Tool ${tool.name} must require 'action' parameter"
            )
        }
    }
}
