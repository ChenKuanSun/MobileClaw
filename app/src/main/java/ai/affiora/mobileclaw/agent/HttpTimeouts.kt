package ai.affiora.mobileclaw.agent

/**
 * Shared HTTP timeout values for the OpenAI-compatible Ktor client.
 *
 * These are used both by the Ktor HttpTimeout plugin installation in ToolsModule
 * and by user-facing error messages in AgentRuntime.formatNetworkError, so they
 * must stay in lock-step. Changing the value here is a single source of truth.
 *
 * Rationale for current values:
 * - REQUEST_MINUTES = 15 — covers the slowest reasoning chains we've observed
 *   (DeepSeek R1, MiniMax M2.x, Nemotron Ultra). These generate thousands of
 *   thinking tokens before emitting the answer in non-streaming mode, often
 *   taking 5-10 minutes on a typical prompt.
 * - CONNECT_SECONDS = 30 — generous for Tailscale / spotty cellular TCP handshake.
 * - SOCKET_MINUTES = 5 — idle-between-packets cap. Reasoning models pause
 *   briefly between thinking and answering phases; 5 min is beyond any observed
 *   pause but still catches legitimately dead sockets before the user waits 15m.
 *
 * Note: Anthropic uses the official SDK's own timeout (defaults to ~10 min).
 * Bedrock, Google, and all OpenAI-compatible providers use these values.
 */
object HttpTimeouts {
    const val REQUEST_MINUTES: Long = 15L
    const val CONNECT_SECONDS: Long = 30L
    const val SOCKET_MINUTES: Long = 5L

    val REQUEST_MS: Long = REQUEST_MINUTES * 60L * 1000L
    val CONNECT_MS: Long = CONNECT_SECONDS * 1000L
    val SOCKET_MS: Long = SOCKET_MINUTES * 60L * 1000L
}
