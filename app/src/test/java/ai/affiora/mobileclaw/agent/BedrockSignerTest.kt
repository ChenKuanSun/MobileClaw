package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.agent.BedrockSigner.AwsCreds
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class BedrockSignerTest {

    private val testCreds = AwsCreds(
        accessKey = "fake-access-key-aaaa",
        secretKey = "fake-secret-key-for-bedrock-signer-tests-only",
        region = "us-east-1",
    )

    private val testUrl = "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3-sonnet/converse"
    private val testBody = """{"messages":[{"role":"user","content":"Hello"}]}""".toByteArray()

    // -----------------------------------------------------------------------
    // 1. Basic signing — required headers present
    // -----------------------------------------------------------------------

    @Test
    fun `sign returns all required headers`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)

        assertThat(result.headers).containsKey("Authorization")
        assertThat(result.headers).containsKey("Host")
        assertThat(result.headers).containsKey("X-Amz-Date")
        // Content-Type is signed but not in result headers — caller sets it via Ktor contentType()
    }

    @Test
    fun `Host matches URL host`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)

        assertThat(result.headers["Host"]).isEqualTo("bedrock-runtime.us-east-1.amazonaws.com")
    }

    @Test
    fun `X-Amz-Date follows yyyyMMddTHHmmssZ format`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)

        assertThat(result.headers["X-Amz-Date"]).matches("\\d{8}T\\d{6}Z")
    }

    // -----------------------------------------------------------------------
    // 2. Authorization header format
    // -----------------------------------------------------------------------

    @Test
    fun `Authorization header starts with AWS4-HMAC-SHA256 Credential`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=")
    }

    @Test
    fun `Authorization header contains access key in Credential`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).contains("Credential=${testCreds.accessKey}/")
    }

    @Test
    fun `Authorization header contains SignedHeaders and Signature`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).contains("SignedHeaders=")
        assertThat(auth).contains("Signature=")
    }

    @Test
    fun `Authorization credential scope contains region and bedrock service`() {
        val result = BedrockSigner.sign(testCreds, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).contains("/${testCreds.region}/bedrock/aws4_request")
    }

    // -----------------------------------------------------------------------
    // 3. Session token present
    // -----------------------------------------------------------------------

    @Test
    fun `session token included in headers when provided`() {
        val credsWithToken = testCreds.copy(sessionToken = "fake-session-token")
        val result = BedrockSigner.sign(credsWithToken, testUrl, testBody)

        assertThat(result.headers).containsKey("X-Amz-Security-Token")
        assertThat(result.headers["X-Amz-Security-Token"]).isEqualTo("fake-session-token")
    }

    @Test
    fun `session token is included in SignedHeaders when provided`() {
        val credsWithToken = testCreds.copy(sessionToken = "fake-session-token")
        val result = BedrockSigner.sign(credsWithToken, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).contains("x-amz-security-token")
    }

    // -----------------------------------------------------------------------
    // 4. No session token
    // -----------------------------------------------------------------------

    @Test
    fun `no X-Amz-Security-Token header when sessionToken is null`() {
        val credsNoToken = testCreds.copy(sessionToken = null)
        val result = BedrockSigner.sign(credsNoToken, testUrl, testBody)

        assertThat(result.headers).doesNotContainKey("X-Amz-Security-Token")
    }

    @Test
    fun `SignedHeaders does not contain x-amz-security-token when sessionToken is null`() {
        val credsNoToken = testCreds.copy(sessionToken = null)
        val result = BedrockSigner.sign(credsNoToken, testUrl, testBody)
        val auth = result.headers["Authorization"]!!

        assertThat(auth).doesNotContain("x-amz-security-token")
    }

    // -----------------------------------------------------------------------
    // 5. Deterministic — same input, same output
    // -----------------------------------------------------------------------

    @Test
    fun `same inputs produce identical signatures`() {
        val result1 = BedrockSigner.sign(testCreds, testUrl, testBody)
        val result2 = BedrockSigner.sign(testCreds, testUrl, testBody)

        assertThat(result1.headers["Authorization"]).isEqualTo(result2.headers["Authorization"])
    }

    // -----------------------------------------------------------------------
    // 6. Different body produces different signature
    // -----------------------------------------------------------------------

    @Test
    fun `different body produces different signature`() {
        val body1 = """{"messages":[{"role":"user","content":"Hello"}]}""".toByteArray()
        val body2 = """{"messages":[{"role":"user","content":"Goodbye"}]}""".toByteArray()

        val result1 = BedrockSigner.sign(testCreds, testUrl, body1)
        val result2 = BedrockSigner.sign(testCreds, testUrl, body2)

        assertThat(result1.headers["Authorization"]).isNotEqualTo(result2.headers["Authorization"])
    }

    // -----------------------------------------------------------------------
    // 7. Known-answer SigV4 tests with fixed timestamp
    // -----------------------------------------------------------------------

    private val fixedTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse("2015-08-30T12:36:00Z")!!

    private val knownCreds = AwsCreds(
        accessKey = "fake-access-key-bbbb",
        secretKey = "fake-secret-key-for-known-answer-test-only",
        region = "us-east-1",
    )

    private val knownUrl = "https://bedrock-runtime.us-east-1.amazonaws.com/model/test-model/converse"
    private val knownBody = """{"messages":[]}""".toByteArray()

    @Test
    fun `known answer produces expected credential scope and date header`() {
        val result = BedrockSigner.sign(knownCreds, knownUrl, knownBody, fixedTimestamp)

        assertThat(result.headers["X-Amz-Date"]).isEqualTo("20150830T123600Z")

        val auth = result.headers["Authorization"]!!
        assertThat(auth).contains("20150830/us-east-1/bedrock/aws4_request")
        assertThat(auth).contains("SignedHeaders=content-type;host;x-amz-date")
    }

    @Test
    fun `known answer with fixed timestamp produces consistent signature`() {
        val result1 = BedrockSigner.sign(knownCreds, knownUrl, knownBody, fixedTimestamp)
        val result2 = BedrockSigner.sign(knownCreds, knownUrl, knownBody, fixedTimestamp)

        assertThat(result1.headers["Authorization"]).isEqualTo(result2.headers["Authorization"])
    }

    @Test
    fun `known answer signature matches expected hex value`() {
        val result = BedrockSigner.sign(knownCreds, knownUrl, knownBody, fixedTimestamp)
        val auth = result.headers["Authorization"]!!

        // Expected signature computed from AWS SigV4 algorithm with the fixed inputs above
        val expectedSignature = "0611ae6836657f6bbf1c486af29ce1d33875ac2ef36ba01bb92c7489129ec8c8"
        assertThat(auth).contains("Signature=$expectedSignature")
    }
}
