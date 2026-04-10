package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse

sealed interface AiBackend {
    suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)? = null,
        onThinkingStarted: (() -> Unit)? = null,
        /** Optional baseUrl override (used by CUSTOM provider for self-hosted endpoints). */
        baseUrlOverride: String? = null,
    ): ClaudeResponse
}
