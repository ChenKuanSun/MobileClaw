package ai.affiora.mobileclaw.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.ResponseTypeValues
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
) {
    companion object {
        private const val TAG = "ConnectorManager"
        private const val TOKEN_PREFIX = "connector_"
        private const val CLIENT_ID_PREFIX = "connector_client_id_"
        private const val VERIFIER_KEY = "connector_pkce_verifier"
        private const val PENDING_CONNECTOR_KEY = "connector_pending_id"
    }

    private val _connectorStatuses = MutableStateFlow<List<Pair<ConnectorConfig, ConnectorStatus>>>(emptyList())
    val connectorStatuses: StateFlow<List<Pair<ConnectorConfig, ConnectorStatus>>> = _connectorStatuses.asStateFlow()

    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    init {
        refreshStatuses()
    }

    fun refreshStatuses() {
        _connectorStatuses.value = Connectors.ALL.map { connector ->
            val token = getToken(connector.id)
            val status = if (token.isNullOrBlank()) {
                ConnectorStatus.NOT_CONNECTED
            } else {
                ConnectorStatus.CONNECTED
            }
            connector to status
        }
    }

    fun getConnectorStatuses(): List<Pair<ConnectorConfig, ConnectorStatus>> {
        return _connectorStatuses.value
    }

    fun getToken(connectorId: String): String? {
        val token = userPreferences.getTokenForProvider("$TOKEN_PREFIX$connectorId")
        return token.ifBlank { null }
    }

    suspend fun saveToken(connectorId: String, token: String) {
        userPreferences.setTokenForProvider("$TOKEN_PREFIX$connectorId", token)
        refreshStatuses()
    }

    suspend fun disconnect(connectorId: String) {
        userPreferences.setTokenForProvider("$TOKEN_PREFIX$connectorId", "")
        refreshStatuses()
    }

    fun getClientId(connectorId: String): String {
        return userPreferences.getTokenForProvider("$CLIENT_ID_PREFIX$connectorId")
    }

    suspend fun saveClientId(connectorId: String, clientId: String) {
        userPreferences.setTokenForProvider("$CLIENT_ID_PREFIX$connectorId", clientId)
    }

    /**
     * Build an Intent that launches the OAuth authorization flow in a Custom Tab.
     * Returns null if the connector doesn't support OAuth or has no client ID configured.
     */
    fun startOAuthFlow(connector: ConnectorConfig): Intent? {
        if (connector.authType != ConnectorAuthType.OAUTH2_PKCE) return null

        val clientId = getClientId(connector.id)
        if (clientId.isBlank()) {
            Log.w(TAG, "No client ID configured for ${connector.name}")
            return null
        }

        val authUrl = connector.authorizationUrl ?: return null
        val tokenUrl = connector.tokenUrl ?: return null

        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(authUrl),
            Uri.parse(tokenUrl),
        )

        // Generate PKCE verifier and challenge
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        // Store verifier and pending connector ID for callback
        savePkceVerifier(verifier)
        savePendingConnectorId(connector.id)

        val requestBuilder = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(connector.redirectUri),
        ).setCodeVerifier(
            verifier,
            challenge,
            "S256",
        )

        if (connector.scopes.isNotEmpty()) {
            requestBuilder.setScopes(connector.scopes)
        }

        val authRequest = requestBuilder.build()
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    /**
     * Handle the OAuth callback intent. Exchanges the authorization code for a token.
     * Returns the connector ID on success.
     */
    suspend fun handleOAuthCallback(intent: Intent): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = AuthorizationResponse.fromIntent(intent)
            val error = net.openid.appauth.AuthorizationException.fromIntent(intent)

            if (error != null) {
                return@withContext Result.failure(Exception("Authorization failed: ${error.errorDescription ?: error.error}"))
            }

            if (response == null) {
                return@withContext Result.failure(Exception("No authorization response"))
            }

            val connectorId = getPendingConnectorId()
            if (connectorId.isNullOrBlank()) {
                return@withContext Result.failure(Exception("No pending connector for callback"))
            }

            val connector = Connectors.ALL.find { it.id == connectorId }
                ?: return@withContext Result.failure(Exception("Unknown connector: $connectorId"))

            val clientId = getClientId(connectorId)

            // Exchange code for token
            val tokenResult = exchangeCodeForToken(response, connector, clientId)
            tokenResult.fold(
                onSuccess = { token ->
                    saveToken(connectorId, token)
                    clearPendingState()
                    Result.success(connectorId)
                },
                onFailure = { Result.failure(it) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "OAuth callback handling failed", e)
            Result.failure(e)
        }
    }

    private suspend fun exchangeCodeForToken(
        authResponse: AuthorizationResponse,
        connector: ConnectorConfig,
        clientId: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tokenRequest = authResponse.createTokenExchangeRequest()

            var resultToken: String? = null
            var resultError: Exception? = null
            val latch = java.util.concurrent.CountDownLatch(1)

            // Use client secret if needed (some providers require it even for PKCE)
            authService.performTokenRequest(tokenRequest) { tokenResponse, exception ->
                if (exception != null) {
                    resultError = Exception("Token exchange failed: ${exception.errorDescription ?: exception.error}")
                } else if (tokenResponse != null) {
                    resultToken = tokenResponse.accessToken
                } else {
                    resultError = Exception("No token in response")
                }
                latch.countDown()
            }

            latch.await()

            if (resultToken != null) {
                Result.success(resultToken!!)
            } else {
                Result.failure(resultError ?: Exception("Unknown token exchange error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Look up connector token for a URL. Used by HttpTool for auto-injection.
     * Returns pair of (header-name, header-value) or null.
     */
    fun getAuthHeaderForUrl(url: String): Pair<String, String>? {
        val urlMappings = mapOf(
            "https://api.notion.com" to "notion",
            "https://api.github.com" to "github",
            "https://slack.com/api" to "slack",
            "https://api.spotify.com" to "spotify",
            "https://www.googleapis.com" to "google",
            "https://api.telegram.org" to "telegram",
            "https://api.openai.com" to "openai",
        )

        for ((baseUrl, connectorId) in urlMappings) {
            if (url.startsWith(baseUrl)) {
                val token = getToken(connectorId) ?: continue
                return when (connectorId) {
                    "notion" -> "Authorization" to "Bearer $token"
                    "github" -> "Authorization" to "Bearer $token"
                    "slack" -> "Authorization" to "Bearer $token"
                    "spotify" -> "Authorization" to "Bearer $token"
                    "google" -> "Authorization" to "Bearer $token"
                    "telegram" -> return null // Token is in the URL path for Telegram
                    "openai" -> "Authorization" to "Bearer $token"
                    else -> "Authorization" to "Bearer $token"
                }
            }
        }
        return null
    }

    // PKCE helpers

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun savePkceVerifier(verifier: String) {
        context.getSharedPreferences("connector_oauth", Context.MODE_PRIVATE)
            .edit().putString(VERIFIER_KEY, verifier).apply()
    }

    private fun savePendingConnectorId(connectorId: String) {
        context.getSharedPreferences("connector_oauth", Context.MODE_PRIVATE)
            .edit().putString(PENDING_CONNECTOR_KEY, connectorId).apply()
    }

    private fun getPendingConnectorId(): String? {
        return context.getSharedPreferences("connector_oauth", Context.MODE_PRIVATE)
            .getString(PENDING_CONNECTOR_KEY, null)
    }

    private fun clearPendingState() {
        context.getSharedPreferences("connector_oauth", Context.MODE_PRIVATE)
            .edit()
            .remove(VERIFIER_KEY)
            .remove(PENDING_CONNECTOR_KEY)
            .apply()
    }
}
