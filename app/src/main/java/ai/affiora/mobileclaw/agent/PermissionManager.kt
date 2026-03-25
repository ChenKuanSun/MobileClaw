package ai.affiora.mobileclaw.agent

import ai.affiora.mobileclaw.data.prefs.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionManager @Inject constructor(
    private val userPreferences: UserPreferences,
) {
    enum class PermissionMode(val id: String, val displayName: String, val description: String) {
        DEFAULT("default", "Default", "Confirm dangerous actions"),
        ALLOWLIST("allowlist", "Allowlist", "Auto-approve selected tools"),
        BYPASS_ALL("bypass_all", "Bypass All", "Auto-approve everything"),
        ;

        companion object {
            fun fromId(id: String): PermissionMode =
                entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mode: StateFlow<PermissionMode> = userPreferences.permissionMode
        .map { PermissionMode.fromId(it) }
        .stateIn(scope, SharingStarted.Eagerly, PermissionMode.DEFAULT)

    val allowedTools: StateFlow<Set<String>> = userPreferences.allowedTools
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Per-session "always allow" decisions (cleared on resetSession). */
    private val _sessionAllowed = MutableStateFlow<Set<String>>(emptySet())
    val sessionAllowed: StateFlow<Set<String>> = _sessionAllowed

    fun setMode(newMode: PermissionMode) {
        scope.launch { userPreferences.setPermissionMode(newMode.id) }
    }

    /** Add tool to permanent allowlist (persisted). */
    fun allowTool(toolName: String) {
        scope.launch { userPreferences.addAllowedTool(toolName) }
    }

    /** Remove tool from permanent allowlist. */
    fun denyTool(toolName: String) {
        scope.launch { userPreferences.removeAllowedTool(toolName) }
    }

    /** Allow tool for this session only. */
    fun sessionAllow(toolName: String) {
        _sessionAllowed.value = _sessionAllowed.value + toolName
    }

    fun shouldAutoApprove(toolName: String): Boolean {
        return when (mode.value) {
            PermissionMode.BYPASS_ALL -> true
            PermissionMode.ALLOWLIST -> toolName in allowedTools.value || toolName in _sessionAllowed.value
            PermissionMode.DEFAULT -> toolName in _sessionAllowed.value
        }
    }

    fun resetSession() {
        _sessionAllowed.value = emptySet()
    }
}
