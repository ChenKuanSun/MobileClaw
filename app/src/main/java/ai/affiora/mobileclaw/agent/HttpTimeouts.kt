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

    // Intentionally matches REQUEST_MINUTES, not shorter. Rationale:
    // socketTimeoutMillis is a per-packet idle timer (OkHttp readTimeout). For the
    // non-streaming path (stream: false), the server buffers the entire response
    // and holds the connection silent while the model thinks — for reasoning
    // models (DeepSeek R1, MiniMax M2.x, Nemotron Ultra) this silence can run
    // several minutes before the first byte arrives. If SOCKET_MINUTES is shorter
    // than REQUEST_MINUTES, the idle timer kills the call before the total cap
    // triggers, making the nominal request limit a lie. v1.2.7 had 5min here and
    // effectively capped reasoning models at 5min despite advertising 15min.
    // The total-request cap (REQUEST_MINUTES) still bounds worst-case resource use.
    const val SOCKET_MINUTES: Long = REQUEST_MINUTES

    val REQUEST_MS: Long = REQUEST_MINUTES * 60L * 1000L
    val CONNECT_MS: Long = CONNECT_SECONDS * 1000L
    val SOCKET_MS: Long = SOCKET_MINUTES * 60L * 1000L
}
