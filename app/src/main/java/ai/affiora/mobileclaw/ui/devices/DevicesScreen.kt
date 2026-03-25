package ai.affiora.mobileclaw.ui.devices

import ai.affiora.mobileclaw.channels.PairedSender
import ai.affiora.mobileclaw.channels.PairingRequest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Refresh service statuses when screen is shown
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // This Device card
            ThisDeviceCard(state)

            // Service Status card
            ServiceStatusCard(state)

            // Permissions card
            if (state.permissions.isNotEmpty()) {
                PermissionsStatusCard(state.permissions)
            }

            // Connected Services
            if (state.connectedServices.isNotEmpty()) {
                ConnectedServicesCard(state)
            }

            // Pending Pairing Requests
            if (state.pendingRequests.isNotEmpty()) {
                PendingRequestsCard(
                    requests = state.pendingRequests,
                    onApprove = { viewModel.approvePairing(it) },
                    onReject = { viewModel.rejectPairing(it) },
                )
            }

            // Active Channels card
            if (state.channelStatuses.isNotEmpty()) {
                ActiveChannelsCard(state)
            }

            // Paired Devices card
            if (state.pairedSenders.isNotEmpty()) {
                PairedDevicesCard(
                    senders = state.pairedSenders,
                    onUnpair = { channelId, senderId ->
                        viewModel.unpairSender(channelId, senderId)
                    },
                )
            }
        }
    }
}

@Composable
private fun ThisDeviceCard(state: DevicesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "This Device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = state.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            InfoRow(label = "Model", value = state.deviceModel)
            Spacer(Modifier.height(4.dp))
            InfoRow(label = "OS", value = state.androidVersion)
            Spacer(Modifier.height(4.dp))
            InfoRow(label = "Available tools", value = state.availableToolsCount.toString())
            Spacer(Modifier.height(4.dp))
            InfoRow(label = "Active skills", value = state.activeSkillsCount.toString())
        }
    }
}

@Composable
private fun ServiceStatusCard(state: DevicesUiState) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "Service Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            ServiceStatusRow(
                icon = Icons.Default.Accessibility,
                label = "Accessibility Service",
                enabled = state.accessibilityEnabled,
            )

            Spacer(Modifier.height(8.dp))

            ServiceStatusRow(
                icon = Icons.Default.Notifications,
                label = "Notification Listener",
                enabled = state.notificationListenerEnabled,
            )

            if (!state.notificationListenerEnabled) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Enable Notification Listener")
                }
            }

            if (!state.accessibilityEnabled) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Enable Accessibility Service")
                }
            }
        }
    }
}

@Composable
private fun ConnectedServicesCard(state: DevicesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "Connected Services",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            state.connectedServices.forEachIndexed { index, service ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (index < state.connectedServices.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsStatusCard(permissions: List<PermissionStatus>) {
    val granted = permissions.count { it.granted }
    val total = permissions.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Permissions ($granted/$total)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            permissions.forEach { perm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = if (perm.granted) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (perm.granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = perm.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (perm.granted) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (granted < total) {
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open App Settings to grant permissions")
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (enabled) "Enabled" else "Disabled",
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (enabled) "Active" else "Off",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PendingRequestsCard(
    requests: List<PairingRequest>,
    onApprove: (PairingRequest) -> Unit,
    onReject: (PairingRequest) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Pending Pairing Requests",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))

            requests.forEachIndexed { index, request ->
                val icon = when (request.channelId) {
                    "telegram" -> Icons.AutoMirrored.Filled.Message
                    "sms" -> Icons.Default.Sms
                    else -> Icons.Default.Cloud
                }
                val channelLabel = when (request.channelId) {
                    "telegram" -> "Telegram"
                    "sms" -> "SMS"
                    else -> request.channelId
                }
                val elapsed = (System.currentTimeMillis() - request.timestamp) / 1000
                val timeAgo = when {
                    elapsed < 60 -> "Just now"
                    elapsed < 3600 -> "${elapsed / 60} min ago"
                    else -> "${elapsed / 3600}h ago"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${request.senderName} ($channelLabel)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "Requested $timeAgo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onApprove(request) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Approve")
                    }
                    Button(
                        onClick = { onReject(request) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Reject")
                    }
                }

                if (index < requests.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveChannelsCard(state: DevicesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "Active Channels",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            state.channelStatuses.forEachIndexed { index, status ->
                val icon = when (status.channelId) {
                    "telegram" -> Icons.AutoMirrored.Filled.Message
                    "sms" -> Icons.Default.Sms
                    "notifications" -> Icons.Default.Notifications
                    else -> Icons.Default.Cloud
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (status.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = status.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${status.pairedCount} paired",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (status.isRunning) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = if (status.isRunning) "Running" else "Stopped",
                        modifier = Modifier.size(18.dp),
                        tint = if (status.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (status.isRunning) "Running" else "Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                if (index < state.channelStatuses.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedDevicesCard(
    senders: List<PairedSender>,
    onUnpair: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "Paired Devices",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            senders.forEachIndexed { index, sender ->
                val icon = when (sender.channelId) {
                    "telegram" -> Icons.AutoMirrored.Filled.Message
                    "sms" -> Icons.Default.Sms
                    "notifications" -> Icons.Default.Notifications
                    else -> Icons.Default.Cloud
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = sender.channelId,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sender.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${sender.channelId} - ${sender.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onUnpair(sender.channelId, sender.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Unpair",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (index < senders.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}
