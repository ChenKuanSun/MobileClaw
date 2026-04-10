package ai.affiora.mobileclaw.agent

import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signer for Bedrock Converse API.
 * Manual implementation — avoids pulling in the full AWS SDK (~40 MB).
 */
object BedrockSigner {

    data class AwsCreds(
        val accessKey: String,
        val secretKey: String,
        val region: String,
        val sessionToken: String? = null,
    )

    data class SignedHeaders(val headers: Map<String, String>)

    private const val SERVICE = "bedrock"
    private const val ALGORITHM = "AWS4-HMAC-SHA256"

    /**
     * Sign a Bedrock POST request, returning all required headers.
     */
    fun sign(
        creds: AwsCreds,
        url: String,
        body: ByteArray,
        timestamp: Date? = null,
    ): SignedHeaders {
        val parsedUrl = URL(url)
        val host = parsedUrl.host
        val path = parsedUrl.path.ifEmpty { "/" }

        val now = timestamp ?: Date()
        val dateFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val amzDate = dateFmt.format(now)
        val dateStamp = dayFmt.format(now)

        val payloadHash = sha256Hex(body)

        // Build signed headers list
        val signedHeaderNames = mutableListOf("content-type", "host", "x-amz-date")
        if (creds.sessionToken != null) signedHeaderNames.add("x-amz-security-token")
        signedHeaderNames.sort()

        val headerMap = sortedMapOf(
            "content-type" to "application/json",
            "host" to host,
            "x-amz-date" to amzDate,
        )
        if (creds.sessionToken != null) {
            headerMap["x-amz-security-token"] = creds.sessionToken
        }

        val canonicalHeaders = headerMap.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = signedHeaderNames.joinToString(";")

        val canonicalRequest = listOf(
            "POST",
            path,
            "",  // no query string
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val credentialScope = "$dateStamp/${creds.region}/$SERVICE/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")

        val signingKey = deriveSigningKey(creds.secretKey, dateStamp, creds.region, SERVICE)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$ALGORITHM Credential=${creds.accessKey}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val result = mutableMapOf(
            "Authorization" to authorization,
            // Content-Type is signed but not returned — caller sets it via contentType()
            "Host" to host,
            "X-Amz-Date" to amzDate,
        )
        if (creds.sessionToken != null) {
            result["X-Amz-Security-Token"] = creds.sessionToken
        }
        return SignedHeaders(result)
    }

    // ── Crypto helpers ──────────────────────────────────────────────────

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
    }

    private fun deriveSigningKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }
}
