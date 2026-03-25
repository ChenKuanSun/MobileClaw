package ai.affiora.mobileclaw.agent

/**
 * How credentials are sent to the provider.
 *
 * - [API_KEY_RAW]: value injected directly into a custom header (no Bearer prefix).
 * - [API_KEY]: Authorization: Bearer <key>.
 * - [BEARER_TOKEN]: Authorization: Bearer <token> (semantically an OAuth token).
 */
enum class AuthType {
    API_KEY,
    API_KEY_RAW,
    BEARER_TOKEN,
}

/**
 * Supported AI providers — mirrors OpenClaw Enterprise's provider-registry.ts exactly.
 * 13 providers, 3 auth types.
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val authType: AuthType,
    val authHeader: String,
    val tokenHint: String,
    val models: List<AiModel>,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic (API Key)",
        baseUrl = "https://api.anthropic.com",
        authType = AuthType.API_KEY_RAW,
        authHeader = "x-api-key",
        tokenHint = "Paste your Anthropic API key (sk-ant-...)",
        models = CLAUDE_MODELS,
        extraHeaders = mapOf(
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14",
        ),
    ),
    ANTHROPIC_TOKEN(
        id = "anthropic-token",
        displayName = "Anthropic (Setup Token)",
        baseUrl = "https://api.anthropic.com",
        authType = AuthType.BEARER_TOKEN,
        authHeader = "Authorization",
        tokenHint = "Run `claude setup-token` in terminal and paste here",
        models = CLAUDE_MODELS,
        extraHeaders = mapOf(
            "anthropic-version" to "2023-06-01",
            "anthropic-beta" to "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14",
        ),
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your OpenAI API key (sk-...)",
        models = OPENAI_MODELS,
    ),
    OPENAI_CODEX(
        id = "openai-codex",
        displayName = "OpenAI Codex",
        baseUrl = "https://api.openai.com",
        authType = AuthType.BEARER_TOKEN,
        authHeader = "Authorization",
        tokenHint = "Paste your OpenAI Codex bearer token",
        models = OPENAI_MODELS,
    ),
    GOOGLE(
        id = "google",
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        authType = AuthType.API_KEY_RAW,
        authHeader = "x-goog-api-key",
        tokenHint = "Paste your Google AI API key",
        models = listOf(
            AiModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
            AiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
        ),
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your OpenRouter API key",
        models = listOf(
            AiModel("anthropic/claude-sonnet-4", "Claude Sonnet 4"),
            AiModel("openai/gpt-4o", "GPT-4o"),
            AiModel("google/gemini-2.5-pro", "Gemini 2.5 Pro"),
            AiModel("deepseek/deepseek-r1", "DeepSeek R1"),
        ),
    ),
    MISTRAL(
        id = "mistral",
        displayName = "Mistral",
        baseUrl = "https://api.mistral.ai",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your Mistral API key",
        models = listOf(
            AiModel("mistral-large-latest", "Mistral Large"),
            AiModel("mistral-medium-latest", "Mistral Medium"),
        ),
    ),
    TOGETHER(
        id = "together",
        displayName = "Together AI",
        baseUrl = "https://api.together.xyz",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your Together AI API key",
        models = listOf(
            AiModel("meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo", "Llama 3.1 405B Instruct Turbo"),
        ),
    ),
    GROQ(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your Groq API key",
        models = listOf(
            AiModel("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile"),
            AiModel("llama-3.1-8b-instant", "Llama 3.1 8B Instant"),
        ),
    ),
    XAI(
        id = "xai",
        displayName = "xAI (Grok)",
        baseUrl = "https://api.x.ai",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your xAI API key",
        models = listOf(
            AiModel("grok-3", "Grok 3"),
            AiModel("grok-3-mini", "Grok 3 Mini"),
        ),
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your DeepSeek API key",
        models = listOf(
            AiModel("deepseek-chat", "DeepSeek Chat"),
            AiModel("deepseek-reasoner", "DeepSeek Reasoner"),
        ),
    ),
    FIREWORKS(
        id = "fireworks",
        displayName = "Fireworks AI",
        baseUrl = "https://api.fireworks.ai/inference",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your Fireworks AI API key",
        models = listOf(
            AiModel("accounts/fireworks/models/llama-v3p1-405b-instruct", "Llama 3.1 405B Instruct"),
        ),
    );

    /** The effective provider ID for API routing (anthropic-token → anthropic, openai-codex → openai). */
    val effectiveId: String
        get() = when (this) {
            ANTHROPIC_TOKEN -> "anthropic"
            OPENAI_CODEX -> "openai"
            else -> id
        }

    /** Whether this is an Anthropic-compatible provider. */
    val isAnthropic: Boolean
        get() = this == ANTHROPIC || this == ANTHROPIC_TOKEN

    /** Whether this is an OpenAI-compatible provider. */
    val isOpenAiCompatible: Boolean
        get() = this in OPENAI_COMPATIBLE_PROVIDERS

    companion object {
        private val OPENAI_COMPATIBLE_PROVIDERS = setOf(
            OPENAI, OPENAI_CODEX, OPENROUTER, MISTRAL,
            TOGETHER, GROQ, XAI, DEEPSEEK, FIREWORKS,
        )

        fun fromId(id: String): AiProvider =
            entries.firstOrNull { it.id == id } ?: ANTHROPIC
    }
}

private val CLAUDE_MODELS = listOf(
    AiModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
    AiModel("claude-haiku-4-5", "Claude Haiku 4.5"),
    AiModel("claude-opus-4-6", "Claude Opus 4.6"),
)

private val OPENAI_MODELS = listOf(
    AiModel("gpt-4o", "GPT-4o"),
    AiModel("gpt-4o-mini", "GPT-4o Mini"),
    AiModel("o3-mini", "o3-mini"),
)

data class AiModel(
    val id: String,
    val displayName: String,
)
