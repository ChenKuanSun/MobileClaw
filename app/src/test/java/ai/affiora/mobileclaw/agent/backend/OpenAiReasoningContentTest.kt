package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.data.model.ContentBlock
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Tests for OpenAiCompatibleBackend response parsing — specifically the
 * reasoning_content handling added to port OpenClaw #66167. MiniMax M2.x,
 * DeepSeek R1, GLM thinking variants return a separate reasoning_content
 * field alongside (or instead of) content.
 */
class OpenAiReasoningContentTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun backendWithResponse(responseBody: String): OpenAiCompatibleBackend {
        val mockEngine = MockEngine { _ ->
            respond(responseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(this@OpenAiReasoningContentTest.json) }
        }
        return OpenAiCompatibleBackend(client, json)
    }

    private suspend fun send(backend: OpenAiCompatibleBackend, response: String) =
        backend.send(
            request = ai.affiora.mobileclaw.data.model.ClaudeRequest(
                model = "minimaxai/minimax-m2.7",
                maxTokens = 1024,
                messages = listOf(
                    ai.affiora.mobileclaw.data.model.ClaudeMessage(
                        role = "user",
                        content = ai.affiora.mobileclaw.data.model.ClaudeContent.Text("hello"),
                    ),
                ),
                system = null,
                tools = null,
            ),
            apiKey = "nvapi-test",
            provider = ai.affiora.mobileclaw.agent.AiProvider.NVIDIA_NIM,
            onTextDelta = null,
            onThinkingStarted = null,
            baseUrlOverride = null,
        )

    @Test
    fun `content and reasoning_content both present — both surfaced`() = runTest {
        val body = """
            {
              "id": "cmpl-1",
              "model": "minimaxai/minimax-m2.7",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "The answer is 42.",
                  "reasoning_content": "Step 1: recall 42 is the classic answer. Step 2: commit."
                },
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val backend = backendWithResponse(body)
        val resp = send(backend, body)

        // Visible content goes to TextBlock, chain-of-thought to thinkingText
        assertThat(resp.content).hasSize(1)
        assertThat((resp.content[0] as ContentBlock.TextBlock).text).isEqualTo("The answer is 42.")
        assertThat(resp.thinkingText).contains("Step 1")
    }

    @Test
    fun `reasoning_content only, empty content — reasoning surfaced as visible answer`() = runTest {
        // The MiniMax M2.7 silent-drop scenario the beta tester hit. Previously
        // this produced an empty chat bubble. Now we fall back to reasoning_content.
        val body = """
            {
              "id": "cmpl-2",
              "model": "minimaxai/minimax-m2.7",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "",
                  "reasoning_content": "I think the answer is 42 but need to double-check..."
                },
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val backend = backendWithResponse(body)
        val resp = send(backend, body)

        assertThat(resp.content).hasSize(1)
        assertThat((resp.content[0] as ContentBlock.TextBlock).text).contains("42")
        assertThat(resp.thinkingText).contains("double-check")
    }

    @Test
    fun `reasoning_content null content — still surfaces reasoning`() = runTest {
        // Some providers serialize content as null rather than empty string.
        val body = """
            {
              "id": "cmpl-3",
              "model": "minimaxai/minimax-m2.7",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "reasoning_content": "Let me think through this carefully"
                },
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val backend = backendWithResponse(body)
        val resp = send(backend, body)

        assertThat(resp.content).hasSize(1)
        assertThat((resp.content[0] as ContentBlock.TextBlock).text).contains("carefully")
    }

    @Test
    fun `no reasoning_content — behavior unchanged for regular responses`() = runTest {
        val body = """
            {
              "id": "cmpl-4",
              "model": "gpt-5.4",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "Hello there!"
                },
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val backend = backendWithResponse(body)
        val resp = send(backend, body)

        assertThat(resp.content).hasSize(1)
        assertThat((resp.content[0] as ContentBlock.TextBlock).text).isEqualTo("Hello there!")
        assertThat(resp.thinkingText).isNull()
    }

    @Test
    fun `empty reasoning_content string treated as absent`() = runTest {
        val body = """
            {
              "id": "cmpl-5",
              "model": "minimaxai/minimax-m2.7",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "short answer",
                  "reasoning_content": ""
                },
                "finish_reason": "stop"
              }]
            }
        """.trimIndent()
        val backend = backendWithResponse(body)
        val resp = send(backend, body)

        assertThat((resp.content[0] as ContentBlock.TextBlock).text).isEqualTo("short answer")
        assertThat(resp.thinkingText).isNull()
    }
}
