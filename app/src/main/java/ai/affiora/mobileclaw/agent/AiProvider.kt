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
 * Supported AI providers: 10 cloud + 1 on-device, 3 auth types.
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
    /** Path appended to baseUrl for chat completions. Default matches OpenAI convention. */
    val chatCompletionsPath: String = "/v1/chat/completions",
    /** True if user must enter their own baseUrl (e.g. self-hosted Ollama / LM Studio / vLLM). */
    val requiresCustomBaseUrl: Boolean = false,
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
            AiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro"),
            AiModel("gemini-3-flash-preview", "Gemini 3 Flash"),
            AiModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
            AiModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
            AiModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite"),
            AiModel("gemma-4-31b-it", "Gemma 4 31B (free)"),
            AiModel("gemma-4-26b-a4b-it", "Gemma 4 26B (free)"),
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
            // Free models
            AiModel("google/gemma-4-31b-it:free", "Gemma 4 31B (free)"),
            AiModel("google/gemma-4-26b-a4b-it:free", "Gemma 4 26B (free)"),
            AiModel("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 3 Super 120B (free)"),
            AiModel("nvidia/nemotron-3-nano-30b-a3b:free", "Nemotron 3 Nano 30B (free)"),
            AiModel("minimax/minimax-m2.5:free", "MiniMax M2.5 (free)"),
            AiModel("stepfun/step-3.5-flash:free", "Step 3.5 Flash (free)"),
            AiModel("arcee-ai/trinity-large-preview:free", "Arcee Trinity Large (free)"),
            AiModel("liquid/lfm-2.5-1.2b-thinking:free", "Liquid LFM 2.5 1.2B Thinking (free)"),
            AiModel("openrouter/free", "Auto (free models router)"),
            // Paid models
            AiModel("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6"),
            AiModel("anthropic/claude-opus-4-6", "Claude Opus 4.6"),
            AiModel("openai/gpt-5.4", "GPT-5.4"),
            AiModel("google/gemini-2.5-pro", "Gemini 2.5 Pro"),
            AiModel("deepseek/deepseek-v4", "DeepSeek V4"),
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
            AiModel("mistral-large-latest", "Mistral Large 3"),
            AiModel("magistral-medium-latest", "Magistral Medium"),
            AiModel("magistral-small-latest", "Magistral Small"),
            AiModel("codestral-latest", "Codestral"),
            AiModel("ministral-8b-latest", "Ministral 8B"),
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
            AiModel("meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8", "Llama 4 Maverick"),
            AiModel("meta-llama/Llama-4-Scout-17B-16E-Instruct", "Llama 4 Scout"),
            AiModel("meta-llama/Meta-Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo"),
            AiModel("deepseek-ai/DeepSeek-V3", "DeepSeek V3"),
            AiModel("Qwen/Qwen3-235B-A22B-fp8", "Qwen 3 235B"),
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
            AiModel("llama4-scout-17b-16e-instruct", "Llama 4 Scout"),
            AiModel("qwen-qwq-32b", "Qwen QwQ 32B"),
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
            AiModel("grok-4", "Grok 4"),
            AiModel("grok-3-beta", "Grok 3"),
            AiModel("grok-3-mini-beta", "Grok 3 Mini"),
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
            AiModel("deepseek-chat", "DeepSeek V4"),
            AiModel("deepseek-reasoner", "DeepSeek R1"),
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
            AiModel("accounts/fireworks/models/llama4-scout-instruct-basic", "Llama 4 Scout"),
            AiModel("accounts/fireworks/models/llama4-maverick-instruct-basic", "Llama 4 Maverick"),
            AiModel("accounts/fireworks/models/deepseek-v3", "DeepSeek V3"),
            AiModel("accounts/fireworks/models/qwen3-235b-a22b", "Qwen 3 235B"),
        ),
    ),
    QWEN(
        id = "qwen",
        displayName = "Qwen (Alibaba)",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your DashScope API key",
        models = listOf(
            AiModel("qwen3.6-plus", "Qwen 3.6 Plus"),
            AiModel("qwen3-max", "Qwen 3 Max"),
            AiModel("qwen3.5-plus", "Qwen 3.5 Plus"),
            AiModel("qwen3.5-flash", "Qwen 3.5 Flash"),
            AiModel("qwen3-coder-plus", "Qwen 3 Coder Plus"),
        ),
    ),
    STEPFUN(
        id = "stepfun",
        displayName = "StepFun",
        baseUrl = "https://api.stepfun.com",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your StepFun API key",
        models = listOf(
            AiModel("step-3.5-flash", "Step 3.5 Flash"),
            AiModel("step-2-16k", "Step 2 16K"),
            AiModel("step-1-256k", "Step 1 256K"),
            AiModel("step-1-128k", "Step 1 128K"),
        ),
    ),
    NVIDIA_NIM(
        id = "nvidia-nim",
        displayName = "NVIDIA NIM",
        baseUrl = "https://integrate.api.nvidia.com",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your NVIDIA API key (nvapi-...) — get one at build.nvidia.com",
        models = listOf(
            AiModel("meta/llama-3.3-70b-instruct", "Llama 3.3 70B"),
            AiModel("meta/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick"),
            AiModel("meta/llama-4-scout-17b-16e-instruct", "Llama 4 Scout"),
            AiModel("nvidia/nemotron-3-super-120b-a12b", "Nemotron 3 Super 120B"),
            AiModel("nvidia/llama-3.1-nemotron-ultra-253b-v1", "Nemotron Ultra 253B"),
            AiModel("deepseek-ai/deepseek-r1", "DeepSeek R1"),
            AiModel("deepseek-ai/deepseek-v3", "DeepSeek V3"),
            AiModel("qwen/qwen3-235b-a22b", "Qwen 3 235B"),
            AiModel("mistralai/mistral-large", "Mistral Large"),
        ),
    ),
    ZAI(
        id = "zai",
        displayName = "z.ai (GLM)",
        baseUrl = "https://api.z.ai/api/paas/v4",
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Paste your z.ai API key — get one at z.ai/model-api",
        models = listOf(
            AiModel("glm-5.1", "GLM 5.1"),
            AiModel("glm-4.7", "GLM 4.7"),
            AiModel("glm-4.6", "GLM 4.6"),
            AiModel("glm-4.5", "GLM 4.5"),
            AiModel("glm-4.5-air", "GLM 4.5 Air"),
            AiModel("glm-4.7-flash", "GLM 4.7 Flash (free)"),
            AiModel("glm-4.5-flash", "GLM 4.5 Flash (free)"),
        ),
        chatCompletionsPath = "/chat/completions",
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom (Ollama / LM Studio / vLLM)",
        baseUrl = "",  // user-provided
        authType = AuthType.API_KEY,
        authHeader = "Authorization",
        tokenHint = "Optional bearer token (leave blank for Ollama). Set Base URL + Model ID below.",
        models = emptyList(),  // user-provided
        // User enters full base URL including /v1 (e.g. http://host:11434/v1) — only /chat/completions appended
        chatCompletionsPath = "/chat/completions",
        requiresCustomBaseUrl = true,
    ),
    BEDROCK(
        id = "bedrock",
        displayName = "Amazon Bedrock",
        baseUrl = "",  // region-specific, built dynamically
        authType = AuthType.API_KEY,
        authHeader = "",
        tokenHint = "Paste JSON: {\"access_key\":\"AKIA...\",\"secret_key\":\"...\",\"region\":\"us-east-1\"}",
        models = listOf(
            AiModel("anthropic.claude-sonnet-4-6-20250514-v1:0", "Claude Sonnet 4.6"),
            AiModel("anthropic.claude-haiku-4-5-20251001-v1:0", "Claude Haiku 4.5"),
            AiModel("us.anthropic.claude-opus-4-6-20250610-v1:0", "Claude Opus 4.6 (cross-region)"),
            AiModel("us.meta.llama4-maverick-17b-instruct-v1:0", "Llama 4 Maverick"),
            AiModel("us.meta.llama4-scout-17b-instruct-v1:0", "Llama 4 Scout"),
            AiModel("amazon.nova-pro-v1:0", "Amazon Nova Pro"),
            AiModel("amazon.nova-lite-v1:0", "Amazon Nova Lite"),
            AiModel("mistral.mistral-large-2407-v1:0", "Mistral Large 2407"),
        ),
    ),
    LOCAL_GEMMA(
        id = "local-gemma",
        displayName = "On-Device (Gemma 4)",
        baseUrl = "",
        authType = AuthType.API_KEY,
        authHeader = "",
        tokenHint = "No API key needed — runs on device",
        models = listOf(
            AiModel("gemma-4-e2b", "Gemma 4 E2B (2.6 GB)"),
            AiModel("gemma-4-e4b", "Gemma 4 E4B (3.7 GB)"),
        ),
    );

    /** The effective provider ID for API routing (anthropic-token → anthropic, openai-codex → openai). */
    val effectiveId: String
        get() = when (this) {
            ANTHROPIC_TOKEN -> "anthropic"
            OPENAI_CODEX -> "openai"
            else -> id
        }

    /** Whether this is an on-device local model provider (no API key, no network). */
    val isLocal: Boolean
        get() = this == LOCAL_GEMMA

    /** Whether this is Amazon Bedrock (uses SigV4 auth + Converse API). */
    val isBedrock: Boolean
        get() = this == BEDROCK

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
            QWEN, STEPFUN, NVIDIA_NIM, ZAI, CUSTOM,
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
    AiModel("gpt-5.4", "GPT-5.4"),
    AiModel("gpt-5.4-mini", "GPT-5.4 Mini"),
    AiModel("gpt-5.4-nano", "GPT-5.4 Nano"),
    AiModel("gpt-4.1", "GPT-4.1"),
    AiModel("gpt-4.1-mini", "GPT-4.1 Mini"),
    AiModel("gpt-4o", "GPT-4o"),
    AiModel("o3-mini", "o3-mini"),
)

data class AiModel(
    val id: String,
    val displayName: String,
)
