package ai.affiora.mobileclaw.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {

    private object Keys {
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val ACTIVE_SKILL_IDS = stringSetPreferencesKey("active_skill_ids")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val ALLOWED_TOOLS = stringSetPreferencesKey("allowed_tools")
        // Per-provider key prefix in EncryptedSharedPreferences
        fun tokenKey(providerId: String) = "token_$providerId"
    }

    /** Encrypted storage for all API tokens — backed by Android Keystore. */
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "androidclaw_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.e("UserPreferences", "Keystore corrupted, resetting", e)
            context.deleteSharedPreferences("androidclaw_secure_prefs")
            // Retry after clearing corrupted prefs
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "androidclaw_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    // Track tokens reactively per provider
    private val _tokenUpdates = MutableStateFlow(0L)

    /** Get the API token for the currently selected provider. */
    val apiKey: Flow<String> = combine(
        context.dataStore.data.map { it[Keys.SELECTED_PROVIDER] ?: DEFAULT_PROVIDER },
        _tokenUpdates,
    ) { providerId, _ ->
        getTokenForProvider(providerId)
    }

    val selectedProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_PROVIDER] ?: DEFAULT_PROVIDER
    }

    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_MODEL] ?: DEFAULT_MODEL
    }

    val activeSkillIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_SKILL_IDS] ?: emptySet()
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val deviceName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEVICE_NAME] ?: ""
    }

    val permissionMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PERMISSION_MODE] ?: "default"
    }

    val allowedTools: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
    }

    /** Read token for a specific provider. */
    fun getTokenForProvider(providerId: String): String {
        return encryptedPrefs.getString(Keys.tokenKey(providerId), "") ?: ""
    }

    /** Check if a provider has a token configured. */
    fun hasTokenForProvider(providerId: String): Boolean {
        return getTokenForProvider(providerId).isNotBlank()
    }

    /** Set token for a specific provider. */
    suspend fun setTokenForProvider(providerId: String, token: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(Keys.tokenKey(providerId), token).apply()
        }
        _tokenUpdates.value = System.currentTimeMillis()
    }

    /** Set token for the currently selected provider. */
    suspend fun setApiKey(apiKey: String) {
        val providerId = selectedProvider.first()
        setTokenForProvider(providerId, apiKey)
    }

    suspend fun setSelectedProvider(provider: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_PROVIDER] = provider
        }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_MODEL] = model
        }
    }

    suspend fun setActiveSkillIds(skillIds: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SKILL_IDS] = skillIds
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
        val flagFile = context.filesDir.resolve(".onboarding_completed")
        if (completed) {
            flagFile.createNewFile()
        } else {
            flagFile.delete()
        }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = name
        }
    }

    suspend fun setPermissionMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PERMISSION_MODE] = mode
        }
    }

    suspend fun setAllowedTools(tools: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ALLOWED_TOOLS] = tools
        }
    }

    suspend fun addAllowedTool(toolName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
            prefs[Keys.ALLOWED_TOOLS] = current + toolName
        }
    }

    suspend fun removeAllowedTool(toolName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.ALLOWED_TOOLS] ?: emptySet()
            prefs[Keys.ALLOWED_TOOLS] = current - toolName
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().clear().apply()
        }
    }

    companion object {
        const val DEFAULT_PROVIDER = "anthropic"
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
    }
}
