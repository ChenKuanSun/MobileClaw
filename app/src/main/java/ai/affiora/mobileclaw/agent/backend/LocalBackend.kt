package ai.affiora.mobileclaw.agent.backend

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.ClaudeApiException
import ai.affiora.mobileclaw.agent.LocalInferenceEngine
import ai.affiora.mobileclaw.agent.LocalModelManager
import ai.affiora.mobileclaw.data.model.ClaudeRequest
import ai.affiora.mobileclaw.data.model.ClaudeResponse

class LocalBackend(
    private val localInferenceEngine: LocalInferenceEngine,
    private val localModelManager: LocalModelManager,
) : AiBackend {

    /** Register tools for local on-device inference. Called by AgentRuntime which holds the tool registry. */
    fun setTools(toolRegistry: Map<String, ai.affiora.mobileclaw.tools.AndroidTool>) {
        localInferenceEngine.setTools(toolRegistry)
    }

    override suspend fun send(
        request: ClaudeRequest,
        apiKey: String,
        provider: AiProvider,
        onTextDelta: ((String) -> Unit)?,
        onThinkingStarted: (() -> Unit)?,
        baseUrlOverride: String?,
    ): ClaudeResponse {
        val modelPath = localModelManager.getModelPath(request.model)
            ?: throw ClaudeApiException(0, "Model '${request.model}' not downloaded. Go to Settings → On-Device Models to download it.")

        if (!localInferenceEngine.isInitialized) {
            localInferenceEngine.initialize(modelPath)
        }

        return localInferenceEngine.generateResponse(
            messages = request.messages,
            systemPrompt = "You are a helpful AI assistant running on the user's Android phone. " +
                "You have tools to control the phone. Use them when the user asks. Be concise.",
            onDelta = onTextDelta,
        )
    }
}
