package ai.affiora.mobileclaw.ui.devices

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import ai.affiora.mobileclaw.channels.ChannelManager
import ai.affiora.mobileclaw.channels.ChannelStatus
import ai.affiora.mobileclaw.channels.PairedSender
import ai.affiora.mobileclaw.channels.PairingRequest
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

data class PermissionStatus(
    val name: String,
    val granted: Boolean,
)

data class DevicesUiState(
    val deviceName: String = "",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val accessibilityEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val permissions: List<PermissionStatus> = emptyList(),
    val availableToolsCount: Int = 0,
    val activeSkillsCount: Int = 0,
    val connectedServices: List<ConnectedServiceInfo> = emptyList(),
    val channelStatuses: List<ChannelStatus> = emptyList(),
    val pairedSenders: List<PairedSender> = emptyList(),
    val pendingRequests: List<PairingRequest> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val connectorManager: ConnectorManager,
    private val skillsManager: SkillsManager,
    private val toolRegistry: Map<String, @JvmSuppressWildcards AndroidTool>,
    private val channelManager: ChannelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        loadDeviceInfo()
        observePendingRequests()
    }

    fun refresh() {
        loadDeviceInfo()
    }

    fun approvePairing(request: PairingRequest) {
        channelManager.approvePairing(request)
        _uiState.update {
            it.copy(
                pendingRequests = channelManager.pendingRequests.value,
                pairedSenders = channelManager.getAllPairedSenders(),
                channelStatuses = channelManager.channelStatuses.value,
            )
        }
    }

    fun rejectPairing(request: PairingRequest) {
        channelManager.rejectPairing(request)
        _uiState.update {
            it.copy(pendingRequests = channelManager.pendingRequests.value)
        }
    }

    private fun observePendingRequests() {
        viewModelScope.launch {
            channelManager.pendingRequests.collect { requests ->
                _uiState.update { it.copy(pendingRequests = requests) }
            }
        }
    }

    fun unpairSender(channelId: String, senderId: String) {
        channelManager.unpairSender(channelId, senderId)
        _uiState.update {
            it.copy(
                pairedSenders = channelManager.getAllPairedSenders(),
                channelStatuses = channelManager.channelStatuses.value,
            )
        }
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

            val permissionChecks = buildList {
                add(PermissionStatus("SMS", checkPerm(android.Manifest.permission.READ_SMS)))
                add(PermissionStatus("Call Log", checkPerm(android.Manifest.permission.READ_CALL_LOG)))
                add(PermissionStatus("Contacts", checkPerm(android.Manifest.permission.READ_CONTACTS)))
                add(PermissionStatus("Calendar", checkPerm(android.Manifest.permission.READ_CALENDAR)))
                add(PermissionStatus("Camera", checkPerm(android.Manifest.permission.CAMERA)))
                add(PermissionStatus("Microphone", checkPerm(android.Manifest.permission.RECORD_AUDIO)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(PermissionStatus("Photos & Media", checkPerm(android.Manifest.permission.READ_MEDIA_IMAGES)))
                    add(PermissionStatus("Notifications", checkPerm(android.Manifest.permission.POST_NOTIFICATIONS)))
                }
            }

            _uiState.update {
                it.copy(
                    deviceName = name,
                    accessibilityEnabled = ClawAccessibilityService.isEnabled(),
                    notificationListenerEnabled = isNotificationListenerEnabled(),
                    permissions = permissionChecks,
                    availableToolsCount = toolRegistry.size,
                    activeSkillsCount = activeSkills.size,
                    connectedServices = connectedServices,
                    channelStatuses = channelManager.channelStatuses.value,
                    pairedSenders = channelManager.getAllPairedSenders(),
                    pendingRequests = channelManager.pendingRequests.value,
                    isLoading = false,
                )
            }
        }
    }

    private fun checkPerm(permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
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
