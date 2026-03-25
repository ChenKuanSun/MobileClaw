package ai.affiora.mobileclaw.tools

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpToolTest {

    private lateinit var httpTool: HttpTool
    private val mockContext: Context = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        // Use ktor MockEngine that returns a simple response for any request
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val httpClient = HttpClient(mockEngine)
        httpTool = HttpTool(mockContext, httpClient)
    }

    private fun requestParams(url: String): Map<String, JsonElement> = mapOf(
        "action" to JsonPrimitive("request"),
        "url" to JsonPrimitive(url),
        "method" to JsonPrimitive("GET"),
    )

    @Test
    fun `localhost is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://localhost/secret"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `127_0_0_1 is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://127.0.0.1/admin"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `10_0_0_1 is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://10.0.0.1/internal"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `192_168_1_1 is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://192.168.1.1/router"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `172_16_0_1 is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://172.16.0.1/internal"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `169_254_169_254 AWS metadata is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://169.254.169.254/latest/meta-data/"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `IPv6 loopback is blocked`() = runTest {
        val result = httpTool.execute(requestParams("http://[::1]/admin"))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("Blocked")
    }

    @Test
    fun `api_anthropic_com is allowed`() = runTest {
        val result = httpTool.execute(requestParams("https://api.anthropic.com/v1/messages"))
        // Should NOT be an SSRF block - either success or non-block error
        if (result is ToolResult.Error) {
            assertThat(result.message).doesNotContain("Blocked")
        } else {
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        }
    }

    @Test
    fun `google_com is allowed`() = runTest {
        val result = httpTool.execute(requestParams("https://google.com"))
        if (result is ToolResult.Error) {
            assertThat(result.message).doesNotContain("Blocked")
        } else {
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
        }
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = httpTool.execute(mapOf("url" to JsonPrimitive("https://example.com")))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("action")
    }

    @Test
    fun `missing url returns error`() = runTest {
        val result = httpTool.execute(mapOf("action" to JsonPrimitive("request")))
        assertThat(result).isInstanceOf(ToolResult.Error::class.java)
        assertThat((result as ToolResult.Error).message).contains("url")
    }

    @Test
    fun `POST without confirmation returns NeedsConfirmation`() = runTest {
        val params = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("request"),
            "url" to JsonPrimitive("https://api.example.com/data"),
            "method" to JsonPrimitive("POST"),
            "body" to JsonPrimitive("""{"key":"value"}"""),
        )
        val result = httpTool.execute(params)
        assertThat(result).isInstanceOf(ToolResult.NeedsConfirmation::class.java)
        val confirmation = result as ToolResult.NeedsConfirmation
        assertThat(confirmation.preview).contains("POST")
        assertThat(confirmation.preview).contains("api.example.com")
    }
}
