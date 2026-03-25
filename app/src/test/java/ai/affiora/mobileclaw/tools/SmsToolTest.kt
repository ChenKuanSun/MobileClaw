package ai.affiora.mobileclaw.tools

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.Telephony
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SmsToolTest {

    private lateinit var smsTool: SmsTool
    private val mockContext: Context = mockk(relaxed = true)
    private val mockContentResolver: ContentResolver = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        every { mockContext.contentResolver } returns mockContentResolver
        smsTool = SmsTool(mockContext)
    }

    @Test
    fun `name is sms`() {
        assertThat(smsTool.name).isEqualTo("sms")
    }

    @Test
    fun `description is not empty`() {
        assertThat(smsTool.description).isNotEmpty()
    }

    @Test
    fun `parameters schema is valid`() {
        val schema = smsTool.parameters
        assertThat((schema["type"] as JsonPrimitive).content).isEqualTo("object")
        assertThat(schema.containsKey("properties")).isTrue()
        assertThat(schema.containsKey("required")).isTrue()
    }

    @Test
    fun `execute returns error when action is missing`() = runTest {
        val params = emptyMap<String, JsonElement>()
        val result = smsTool.execute(params)
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("action")
    }

    @Test
    fun `execute returns error for unknown action`() = runTest {
        val params = mapOf<String, JsonElement>("action" to JsonPrimitive("delete"))
        val result = smsTool.execute(params)
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("delete")
    }

    @Test
    fun `search queries content resolver with correct URI`() = runTest {
        val cursor = createSmsCursor(emptyList())
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val params = mapOf<String, JsonElement>("action" to JsonPrimitive("search"))
        smsTool.execute(params)

        verify {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                isNull(),
                isNull(),
                any()
            )
        }
    }

    @Test
    fun `search with since filter builds correct selection`() = runTest {
        val cursor = createSmsCursor(emptyList())
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("search"),
            "since" to JsonPrimitive(1700000000000L)
        )
        smsTool.execute(params)

        verify {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                eq("${Telephony.Sms.DATE} > ?"),
                eq(arrayOf("1700000000000")),
                any()
            )
        }
    }

    @Test
    fun `search with from filter builds correct selection`() = runTest {
        val cursor = createSmsCursor(emptyList())
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("search"),
            "from" to JsonPrimitive("+1234567890")
        )
        smsTool.execute(params)

        verify {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                eq("${Telephony.Sms.ADDRESS} LIKE ?"),
                eq(arrayOf("%+1234567890%")),
                any()
            )
        }
    }

    @Test
    fun `search returns success with messages`() = runTest {
        val messages = listOf(
            SmsRow("+1234567890", "Hello", 1700000000000L, 1, 1),
            SmsRow("+0987654321", "World", 1700000001000L, 2, 0)
        )
        val cursor = createSmsCursor(messages)
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val params = mapOf<String, JsonElement>("action" to JsonPrimitive("search"))
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        val data = (result as ToolResult.Success).data
        assertThat(data).contains("+1234567890")
        assertThat(data).contains("Hello")
        assertThat(data).contains("+0987654321")
        assertThat(data).contains("World")
    }

    @Test
    fun `search respects limit parameter`() = runTest {
        val messages = listOf(
            SmsRow("+1111111111", "One", 1700000000000L, 1, 1),
            SmsRow("+2222222222", "Two", 1700000001000L, 1, 1),
            SmsRow("+3333333333", "Three", 1700000002000L, 1, 1)
        )
        val cursor = createSmsCursor(messages)
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("search"),
            "limit" to JsonPrimitive(1)
        )
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        val data = (result as ToolResult.Success).data
        assertThat(data).contains("+1111111111")
        assertThat(data).doesNotContain("+2222222222")
        assertThat(data).doesNotContain("+3333333333")
    }

    @Test
    fun `search returns error when cursor is null`() = runTest {
        every {
            mockContentResolver.query(
                eq(Telephony.Sms.CONTENT_URI),
                any(),
                any(),
                any(),
                any()
            )
        } returns null

        val params = mapOf<String, JsonElement>("action" to JsonPrimitive("search"))
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Failed")
    }

    @Test
    fun `send returns NeedsConfirmation with preview`() = runTest {
        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("send"),
            "to" to JsonPrimitive("+1234567890"),
            "body" to JsonPrimitive("Test message")
        )
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.NeedsConfirmation::class.java)
        val confirmation = result as ToolResult.NeedsConfirmation
        assertThat(confirmation.preview).contains("+1234567890")
        assertThat(confirmation.preview).contains("Test message")
        assertThat(confirmation.requestId).isNotEmpty()
    }

    @Test
    fun `send returns error when to is missing`() = runTest {
        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("send"),
            "body" to JsonPrimitive("Test message")
        )
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("to")
    }

    @Test
    fun `send returns error when body is missing`() = runTest {
        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("send"),
            "to" to JsonPrimitive("+1234567890")
        )
        val result = smsTool.execute(params)

        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("body")
    }

    // --- Helpers ---

    private data class SmsRow(
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Int
    )

    private fun createSmsCursor(rows: List<SmsRow>): Cursor {
        val columns = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        val cursor = MatrixCursor(columns)
        for (row in rows) {
            cursor.addRow(arrayOf(row.address, row.body, row.date, row.type, row.read))
        }
        return cursor
    }
}
