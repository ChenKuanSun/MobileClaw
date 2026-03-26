package ai.affiora.mobileclaw.ui.devices

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.connectors.ConnectorStatus
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.skills.SkillsManager
import ai.affiora.mobileclaw.tools.AndroidTool
import ai.affiora.mobileclaw.tools.ClawAccessibilityService
import ai.affiora.mobileclaw.tools.ClawNotificationListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectedServiceInfo(
    val name: String,
    val icon: String,
    val status: ConnectorStatus,
)

data class DevicesUiState(
    val deviceName: String = "",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val accessibilityEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val availableToolsCount: Int = 0,
    val activeSkillsCount: Int = 0,
    val connectedServices: List<ConnectedServiceInfo> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val connectorManager: ConnectorManager,
    private val skillsManager: SkillsManager,
    private val toolRegistry: Map<String, @JvmSuppressWildcards AndroidTool>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        loadDeviceInfo()
    }

    fun refresh() {
        loadDeviceInfo()
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            val name = userPreferences.deviceName.first().ifBlank {
                "${Build.MANUFACTURER} ${Build.MODEL}"
            }

            val activeSkills = skillsManager.getActiveSkills()

            val connectorStatuses = connectorManager.getConnectorStatuses()
            val connectedServices = connectorStatuses
                .filter { (_, status) -> status == ConnectorStatus.CONNECTED }
                .map { (config, status) ->
                    ConnectedServiceInfo(
                        name = config.name,
                        icon = config.icon,
                        status = status,
                    )
                }

            _uiState.update {
                it.copy(
                    deviceName = name,
                    accessibilityEnabled = ClawAccessibilityService.isEnabled(),
                    notificationListenerEnabled = isNotificationListenerEnabled(),
                    availableToolsCount = toolRegistry.size,
                    activeSkillsCount = activeSkills.size,
                    connectedServices = connectedServices,
                    isLoading = false,
                )
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val componentName = ComponentName(context, ClawNotificationListener::class.java).flattenToString()
        return flat.contains(componentName)
    }
}
