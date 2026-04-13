package ai.affiora.mobileclaw.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FormatApiErrorTest {

    @Test
    fun `401 unauthorized returns settings hint`() {
        val msg = formatApiError(ClaudeApiException(401, "Invalid API key"))
        assertThat(msg).contains("Unauthorized")
        assertThat(msg).contains("Settings")
    }

    @Test
    fun `429 rate limited includes body and retry note`() {
        val msg = formatApiError(ClaudeApiException(429, "Too many requests"))
        assertThat(msg).contains("429")
        assertThat(msg).contains("Too many requests")
        assertThat(msg).contains("retry")
    }

    @Test
    fun `OpenRouter 404 guardrail returns privacy settings URL`() {
        val body = """{"error":{"message":"No endpoints available matching your guardrail restrictions and data policy.","code":404}}"""
        val msg = formatApiError(ClaudeApiException(404, body))
        assertThat(msg).contains("OpenRouter")
        assertThat(msg).contains("data policy")
        assertThat(msg).contains("https://openrouter.ai/settings/privacy")
    }

    @Test
    fun `OpenRouter 404 guardrail is case insensitive on keyword`() {
        val body = """{"error":{"message":"Guardrail blocked this route"}}"""
        val msg = formatApiError(ClaudeApiException(404, body))
        assertThat(msg).contains("openrouter.ai/settings/privacy")
    }

    @Test
    fun `non-guardrail 404 does not mention OpenRouter`() {
        val msg = formatApiError(ClaudeApiException(404, "Model not found"))
        assertThat(msg).doesNotContain("openrouter.ai")
        assertThat(msg).contains("Model not found")
    }

    @Test
    fun `402 insufficient credits returns top-up hint`() {
        val msg = formatApiError(ClaudeApiException(402, """{"error":{"message":"Insufficient credits"}}"""))
        assertThat(msg).contains("credits")
        assertThat(msg).contains("Top up")
    }

    @Test
    fun `402 credit card declined does not trigger OpenRouter hint`() {
        // Stripe-mediated billing errors from OpenAI/Mistral/Together should not
        // be misclassified as OpenRouter "insufficient credits".
        val body = """{"error":{"message":"Your credit card was declined. Please update your payment method."}}"""
        val msg = formatApiError(ClaudeApiException(402, body))
        assertThat(msg).doesNotContain("Top up your provider account")
        assertThat(msg).contains("402")
        assertThat(msg).contains("credit card was declined")
    }

    @Test
    fun `404 with guardrail from Bedrock-flavored body does NOT trigger OpenRouter hint`() {
        // Bedrock Guardrails return 400 not 404, but defend against future wording
        // by excluding any 404 body that mentions "bedrock".
        val body = """Guardrail arn:aws:bedrock:us-east-1:123:guardrail/abc not found"""
        val msg = formatApiError(ClaudeApiException(404, body))
        assertThat(msg).doesNotContain("openrouter.ai")
        assertThat(msg).contains("404")
    }

    @Test
    fun `Bedrock 403 InvokeModel returns model access hint`() {
        val body = """User arn:aws:iam::123:user/foo is not authorized to perform: bedrock:InvokeModel on resource"""
        val msg = formatApiError(ClaudeApiException(403, body))
        assertThat(msg).contains("Bedrock")
        assertThat(msg).contains("Model access")
    }

    @Test
    fun `generic 500 falls through to default formatter`() {
        val msg = formatApiError(ClaudeApiException(500, "Internal server error"))
        assertThat(msg).contains("500")
        assertThat(msg).contains("Internal server error")
    }

    // ── formatNetworkError tests ─────────────────────────────────────────

    @Test
    fun `UnknownHostException mentions Tailscale MagicDNS hint`() {
        val msg = formatNetworkError(java.net.UnknownHostException("desktop.tail-net.ts.net"))
        assertThat(msg).contains("Could not resolve")
        assertThat(msg).contains("Tailscale")
        assertThat(msg).contains("MagicDNS")
        assertThat(msg).doesNotContain("No internet connection")
    }

    @Test
    fun `ConnectException mentions OLLAMA_HOST and bind hint`() {
        val msg = formatNetworkError(java.net.ConnectException("Connection refused"))
        assertThat(msg).contains("Connection refused")
        assertThat(msg).contains("Ollama")
        assertThat(msg).contains("OLLAMA_HOST=0.0.0.0")
        assertThat(msg).doesNotContain("No internet connection")
    }

    @Test
    fun `SocketTimeoutException suggests checking Tailscale status`() {
        val msg = formatNetworkError(java.net.SocketTimeoutException("read timeout"))
        assertThat(msg).contains("timeout")
        assertThat(msg).contains("Tailscale")
    }

    @Test
    fun `unknown network exception falls through to message`() {
        val msg = formatNetworkError(java.net.SocketException("Broken pipe"))
        assertThat(msg).contains("Network error")
        assertThat(msg).contains("Broken pipe")
    }
}
