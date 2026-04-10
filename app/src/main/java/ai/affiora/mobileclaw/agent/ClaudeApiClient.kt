package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.agent.backend.AiBackend
import ai.affiora.mobileclaw.agent.backend.AnthropicBackend
import ai.affiora.mobileclaw.agent.backend.BedrockBackend
import ai.affiora.mobileclaw.agent.backend.GoogleBackend
import ai.affiora.mobileclaw.agent.backend.LocalBackend
import ai.affiora.mobileclaw.agent.backend.OpenAiCompatibleBackend
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

class ClaudeApiException(
    val statusCode: Int,
    val errorBody: String,
) : Exception("API error $statusCode: $errorBody")

@Singleton
class ClaudeApiClient @Inject constructor(
    private val userPreferences: UserPreferences,
    localInferenceEngine: LocalInferenceEngine,
    localModelManager: LocalModelManager,
) {

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Ktor client kept only for non-Anthropic providers (OpenAI-compatible, Google, Bedrock). */
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        install(Logging) {
            level = LogLevel.INFO
            sanitizeHeader { it == "x-api-key" || it == "Authorization" || it == "x-goog-api-key" }
        }
    }

    // ── Backend instances ──────────────────────────────────────────────────

    private val localBackend = LocalBackend(localInferenceEngine, localModelManager)
    private val anthropicBackend = AnthropicBackend(jsonSerializer)
    private val bedrockBackend = BedrockBackend(httpClient, jsonSerializer)
    private val googleBackend = GoogleBackend(httpClient, jsonSerializer)
    private val openAiCompatibleBackend = OpenAiCompatibleBackend(httpClient, jsonSerializer)

    companion object {
        private const val TAG = "AiApiClient"
        private const val MAX_RETRIES = 3
        private val RETRYABLE_STATUS_CODES = setOf(429, 503, 529)
    }

    private fun backendFor(provider: AiProvider): AiBackend = when {
        provider.isLocal -> localBackend
        provider.isBedrock -> bedrockBackend
        provider.isAnthropic -> anthropicBackend
        provider == AiProvider.GOOGLE -> googleBackend
        else -> openAiCompatibleBackend
    }

    private suspend fun <T> withRetry(
        onRetry: ((attempt: Int, statusCode: Int, delayMs: Long) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                return block()
            } catch (e: ClaudeApiException) {
                if (e.statusCode !in RETRYABLE_STATUS_CODES) throw e
                if (attempt >= MAX_RETRIES) throw e
                lastException = e
                val baseDelayMs = 1000L shl attempt
                val jitter = Random.nextLong(0, baseDelayMs / 2)
                val totalDelay = baseDelayMs + jitter
                Log.w(TAG, "Retryable error ${e.statusCode}, attempt ${attempt + 1}/$MAX_RETRIES, backing off ${totalDelay}ms")
                onRetry?.invoke(attempt + 1, e.statusCode, totalDelay)
                delay(totalDelay)
            }
        }
        throw lastException ?: IllegalStateException("Retry exhausted")
    }

    suspend fun sendMessage(
        request: ClaudeRequest,
        onTextDelta: ((String) -> Unit)? = null,
        onThinkingStarted: (() -> Unit)? = null,
        onRetry: ((attempt: Int, statusCode: Int, delayMs: Long) -> Unit)? = null,
    ): ClaudeResponse {
        val apiKey = userPreferences.apiKey.first()
        val providerId = userPreferences.selectedProvider.first()
        val provider = AiProvider.fromId(providerId)
        val backend = backendFor(provider)

        // Local models don't need an API key or network
        if (provider.isLocal) {
            return backend.send(request, apiKey, provider, onTextDelta, onThinkingStarted)
        }

        // CUSTOM provider needs a user-supplied base URL; API key is optional (Ollama has no auth)
        val baseUrlOverride: String? = if (provider.requiresCustomBaseUrl) {
            val custom = userPreferences.getBaseUrlForProvider(provider.id)
            if (custom.isBlank()) {
                throw ClaudeApiException(0, "Base URL not configured for ${provider.displayName}. Set it in Settings.")
            }
            custom
        } else {
            null
        }

        if (apiKey.isBlank() && !provider.requiresCustomBaseUrl) {
            throw ClaudeApiException(401, "API key not configured. Go to Settings to add your ${provider.displayName} token.")
        }

        return withRetry(onRetry) {
            backend.send(request, apiKey, provider, onTextDelta, onThinkingStarted, baseUrlOverride)
        }
    }

    /** Register tools for local on-device inference. Called by AgentRuntime which holds the tool registry. */
    fun setLocalTools(toolRegistry: Map<String, ai.affiora.mobileclaw.tools.AndroidTool>) {
        localBackend.setTools(toolRegistry)
    }

    fun close() {
        httpClient.close()
    }
}
