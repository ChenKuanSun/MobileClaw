package ai.affiora.mobileclaw.channels

import android.content.Context
import ai.affiora.mobileclaw.connectors.ConnectorManager
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests credential parsing for each communication channel.
 *
 * Strategy: loadCreds() is private, so we test via observable behavior —
 * call start() with various ConnectorManager.getToken() return values
 * and assert isRunning is true (valid creds) or false (invalid/missing).
 */
class ChannelCredentialParsingTest {

    private lateinit var connectorManager: ConnectorManager
    private lateinit var httpClient: HttpClient
    private lateinit var context: Context
    private lateinit var channelManager: ChannelManager

    @BeforeEach
    fun setup() {
        connectorManager = mockk(relaxed = true)
        httpClient = HttpClient(MockEngine { _ ->
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(WebSockets)
        }
        context = mockk(relaxed = true)
        channelManager = mockk(relaxed = true)
    }

    // -----------------------------------------------------------------------
    // MatrixChannel
    // -----------------------------------------------------------------------

    @Nested
    inner class MatrixChannelCreds {

        private fun buildChannel(): MatrixChannel {
            val ch = MatrixChannel(connectorManager, httpClient, context)
            ch.channelManager = channelManager
            return ch
        }

        @Test
        fun `valid JSON with all fields sets isRunning true`() = runTest {
            every { connectorManager.getToken("matrix") } returns """
                {"homeserver":"https://matrix.org","user_id":"@bot:matrix.org","access_token":"syt_abc123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `null token sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns null

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing homeserver sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns """
                {"user_id":"@bot:matrix.org","access_token":"syt_abc123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing user_id sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns """
                {"homeserver":"https://matrix.org","access_token":"syt_abc123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing access_token sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns """
                {"homeserver":"https://matrix.org","user_id":"@bot:matrix.org"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `malformed JSON sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns "not-json{{"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `empty string sets isRunning false`() = runTest {
            every { connectorManager.getToken("matrix") } returns ""

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `homeserver trailing slash is trimmed`() = runTest {
            // Valid creds — the channel should start. The trailing slash stripping
            // is internal but we verify start() succeeds (doesn't crash).
            every { connectorManager.getToken("matrix") } returns """
                {"homeserver":"https://matrix.org/","user_id":"@bot:matrix.org","access_token":"syt_abc"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }
    }

    // -----------------------------------------------------------------------
    // SlackChannel
    // -----------------------------------------------------------------------

    @Nested
    inner class SlackChannelCreds {

        private fun buildChannel(): SlackChannel {
            val ch = SlackChannel(connectorManager, httpClient, context)
            ch.channelManager = channelManager
            return ch
        }

        @Test
        fun `valid xapp and xoxb tokens sets isRunning true`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"app_token":"xapp-1-A123-456-abc","bot_token":"xoxb-123-456-abc"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `null token sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns null

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `app_token without xapp prefix sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"app_token":"bad-prefix-token","bot_token":"xoxb-123-456-abc"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `bot_token without xoxb prefix sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"app_token":"xapp-1-A123-456-abc","bot_token":"bad-prefix-token"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing app_token sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"bot_token":"xoxb-123-456-abc"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing bot_token sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"app_token":"xapp-1-A123-456-abc"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `malformed JSON sets isRunning false`() = runTest {
            every { connectorManager.getToken("slack_app") } returns "{broken"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `xoxp user token rejected for bot_token`() = runTest {
            every { connectorManager.getToken("slack_app") } returns """
                {"app_token":"xapp-1-A123-456-abc","bot_token":"xoxp-user-token"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }
    }

    // -----------------------------------------------------------------------
    // FeishuChannel
    // -----------------------------------------------------------------------

    @Nested
    inner class FeishuChannelCreds {

        private fun buildChannel(): FeishuChannel {
            val ch = FeishuChannel(connectorManager, httpClient, context)
            ch.channelManager = channelManager
            return ch
        }

        @Test
        fun `valid creds with default domain sets isRunning true`() = runTest {
            every { connectorManager.getToken("feishu") } returns """
                {"app_id":"cli_abc123","app_secret":"secret123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `valid creds with explicit domain sets isRunning true`() = runTest {
            every { connectorManager.getToken("feishu") } returns """
                {"app_id":"cli_abc123","app_secret":"secret123","domain":"open.larksuite.com"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `null token sets isRunning false`() = runTest {
            every { connectorManager.getToken("feishu") } returns null

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing app_id sets isRunning false`() = runTest {
            every { connectorManager.getToken("feishu") } returns """
                {"app_secret":"secret123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing app_secret sets isRunning false`() = runTest {
            every { connectorManager.getToken("feishu") } returns """
                {"app_id":"cli_abc123"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `malformed JSON sets isRunning false`() = runTest {
            every { connectorManager.getToken("feishu") } returns "[[invalid"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `empty string sets isRunning false`() = runTest {
            every { connectorManager.getToken("feishu") } returns ""

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }
    }

    // -----------------------------------------------------------------------
    // WhatsAppChannel
    // -----------------------------------------------------------------------

    @Nested
    inner class WhatsAppChannelCreds {

        private fun buildChannel(): WhatsAppChannel {
            val ch = WhatsAppChannel(connectorManager, httpClient, context)
            ch.channelManager = channelManager
            return ch
        }

        @Test
        fun `valid creds sets isRunning true`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns """
                {"access_token":"EAAxxxxxxx","phone_number_id":"123456789012345"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `null token sets isRunning false`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns null

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing access_token sets isRunning false`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns """
                {"phone_number_id":"123456789012345"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing phone_number_id sets isRunning false`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns """
                {"access_token":"EAAxxxxxxx"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `malformed JSON sets isRunning false`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns "not json"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `empty object sets isRunning false`() = runTest {
            every { connectorManager.getToken("whatsapp") } returns "{}"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }
    }

    // -----------------------------------------------------------------------
    // TeamsChannel
    // -----------------------------------------------------------------------

    @Nested
    inner class TeamsChannelCreds {

        private fun buildChannel(): TeamsChannel {
            val ch = TeamsChannel(connectorManager, httpClient, context)
            ch.channelManager = channelManager
            return ch
        }

        @Test
        fun `valid creds with service_url sets isRunning true`() = runTest {
            every { connectorManager.getToken("teams") } returns """
                {"tenant_id":"tid","client_id":"cid","client_secret":"csec","service_url":"https://smba.trafficmanager.net/amer/"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `valid creds without service_url defaults and sets isRunning true`() = runTest {
            every { connectorManager.getToken("teams") } returns """
                {"tenant_id":"tid","client_id":"cid","client_secret":"csec"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `null token sets isRunning false`() = runTest {
            every { connectorManager.getToken("teams") } returns null

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing tenant_id defaults to multi-tenant and starts`() = runTest {
            // tenant_id is optional — defaults to "botframework.com" for multi-tenant Bot Framework apps.
            // Single-tenant apps must provide an explicit tenant GUID.
            every { connectorManager.getToken("teams") } returns """
                {"client_id":"cid","client_secret":"csec"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isTrue()
        }

        @Test
        fun `missing client_id sets isRunning false`() = runTest {
            every { connectorManager.getToken("teams") } returns """
                {"tenant_id":"tid","client_secret":"csec"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `missing client_secret sets isRunning false`() = runTest {
            every { connectorManager.getToken("teams") } returns """
                {"tenant_id":"tid","client_id":"cid"}
            """.trimIndent()

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `malformed JSON sets isRunning false`() = runTest {
            every { connectorManager.getToken("teams") } returns "}{bad"

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }

        @Test
        fun `empty string sets isRunning false`() = runTest {
            every { connectorManager.getToken("teams") } returns ""

            val channel = buildChannel()
            channel.start()

            assertThat(channel.isRunning).isFalse()
        }
    }
}
