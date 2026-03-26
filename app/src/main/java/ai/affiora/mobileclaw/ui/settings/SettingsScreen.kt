package ai.affiora.mobileclaw.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import ai.affiora.mobileclaw.BuildConfig
import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.connectors.ConnectorAuthType
import ai.affiora.mobileclaw.connectors.ConnectorConfig
import ai.affiora.mobileclaw.connectors.ConnectorStatus
import ai.affiora.mobileclaw.tools.ClawAccessibilityService
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private val pageTitles = mapOf(
    "main" to "Settings",
    "provider" to "AI Provider",
    "connectors" to "Connectors",
    "permissions" to "Permissions",
    "device" to "Device",
    "data" to "Data & Storage",
    "about" to "About",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val providerTokens by viewModel.providerTokens.collectAsState()
    val clearHistoryCompleted by viewModel.clearHistoryCompleted.collectAsState()
    val permissionMode by viewModel.permissionMode.collectAsState()
    val allowedTools by viewModel.allowedTools.collectAsState()
    val connectorStatuses by viewModel.connectorStatuses.collectAsState()

    val context = LocalContext.current

    val oauthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.handleOAuthCallback(result.data!!)
        }
    }

    var currentPage by rememberSaveable { mutableStateOf("main") }
    var showClearDialog by remember { mutableStateOf(false) }

    if (clearHistoryCompleted) {
        viewModel.dismissClearHistoryConfirmation()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear History") },
            text = { Text("This will permanently delete all conversation history. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearConversationHistory()
                        showClearDialog = false
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        pageTitles[currentPage] ?: "Settings",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (currentPage != "main") {
                        IconButton(onClick = { currentPage = "main" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = {
                if (targetState == "main") {
                    (slideInHorizontally { -it } + fadeIn())
                        .togetherWith(slideOutHorizontally { it } + fadeOut())
                } else {
                    (slideInHorizontally { it } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it } + fadeOut())
                }
            },
            label = "settings_page",
        ) { page ->
            when (page) {
                "main" -> SettingsMainList(
                    selectedProvider = selectedProvider,
                    selectedModel = selectedModel,
                    providerTokens = providerTokens,
                    connectorStatuses = connectorStatuses,
                    permissionMode = permissionMode,
                    deviceName = deviceName,
                    onNavigate = { currentPage = it },
                )
                "provider" -> ProviderPage(viewModel, selectedProvider, selectedModel, providerTokens)
                "connectors" -> ConnectorsPage(viewModel, connectorStatuses, oauthLauncher)
                "permissions" -> PermissionsPage(viewModel, permissionMode, allowedTools)
                "device" -> DevicePage(viewModel, deviceName)
                "data" -> DataPage(onClearHistory = { showClearDialog = true })
                "about" -> AboutPage()
                else -> SettingsMainList(
                    selectedProvider = selectedProvider,
                    selectedModel = selectedModel,
                    providerTokens = providerTokens,
                    connectorStatuses = connectorStatuses,
                    permissionMode = permissionMode,
                    deviceName = deviceName,
                    onNavigate = { currentPage = it },
                )
            }
        }
    }
}

// ── Main Settings List ──────────────────────────────────────────────────

@Composable
private fun SettingsMainList(
    selectedProvider: AiProvider,
    selectedModel: String,
    providerTokens: List<ProviderTokenState>,
    connectorStatuses: List<Pair<ConnectorConfig, ConnectorStatus>>,
    permissionMode: PermissionManager.PermissionMode,
    deviceName: String,
    onNavigate: (String) -> Unit,
) {
    val modelDisplay = selectedProvider.models
        .firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
    val configuredTokens = providerTokens.count { it.hasToken }
    val connectedCount = connectorStatuses.count { it.second == ConnectorStatus.CONNECTED }
    val totalConnectors = connectorStatuses.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── GENERAL ──
        SectionHeader("GENERAL")

        SettingsRow(
            icon = "\uD83E\uDD16",
            title = "AI Provider",
            subtitle = if (configuredTokens > 0) "$modelDisplay \u00b7 $configuredTokens key${if (configuredTokens != 1) "s" else ""}"
            else "No keys configured",
            onClick = { onNavigate("provider") },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = "\uD83D\uDD17",
            title = "Connectors",
            subtitle = "$connectedCount of $totalConnectors connected",
            onClick = { onNavigate("connectors") },
        )

        // ── SECURITY ──
        SectionHeader("SECURITY")

        SettingsRow(
            icon = "\uD83D\uDEE1\uFE0F",
            title = "Permissions",
            subtitle = "${permissionMode.displayName} mode",
            onClick = { onNavigate("permissions") },
        )

        // ── SYSTEM ──
        SectionHeader("SYSTEM")

        SettingsRow(
            icon = "\uD83D\uDCF1",
            title = "Device",
            subtitle = deviceName.ifBlank { "Configure device" },
            onClick = { onNavigate("device") },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = "\uD83D\uDCBE",
            title = "Data & Storage",
            subtitle = "Clear history",
            onClick = { onNavigate("data") },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        SettingsRow(
            icon = "\u2139\uFE0F",
            title = "About",
            subtitle = "v${BuildConfig.VERSION_NAME}",
            onClick = { onNavigate("about") },
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Text(
                icon,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ── AI Provider Page ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPage(
    viewModel: SettingsViewModel,
    selectedProvider: AiProvider,
    selectedModel: String,
    providerTokens: List<ProviderTokenState>,
) {
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var showRemoveKeyDialog by remember { mutableStateOf<AiProvider?>(null) }

    val configuredKeys = providerTokens.filter { it.hasToken }
    val availableModels = viewModel.getAvailableModels()
    val modelDisplay = selectedProvider.models
        .firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel

    // Add Key dialog
    if (showAddKeyDialog) {
        AddKeyDialog(
            configuredProviderIds = configuredKeys.map { it.provider.id }.toSet(),
            onDismiss = { showAddKeyDialog = false },
            onAdd = { providerId, token ->
                viewModel.addKey(providerId, token)
                showAddKeyDialog = false
            },
        )
    }

    // Remove Key confirmation
    showRemoveKeyDialog?.let { provider ->
        AlertDialog(
            onDismissRequest = { showRemoveKeyDialog = null },
            title = { Text("Remove Key") },
            text = { Text("Remove the API key for ${provider.displayName}? You can re-add it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeKey(provider.id)
                        showRemoveKeyDialog = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveKeyDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Active model display ──
        if (configuredKeys.isNotEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$modelDisplay (${selectedProvider.displayName})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "No API keys configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add a key below to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Your Keys ──
        Text(
            "YOUR KEYS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        if (configuredKeys.isEmpty()) {
            Text(
                "No keys added yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            configuredKeys.forEach { tokenState ->
                val maskedKey = maskToken(tokenState.token)
                ListItem(
                    headlineContent = {
                        Text(
                            tokenState.provider.displayName,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    supportingContent = {
                        Text(
                            maskedKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Text(
                            "\uD83D\uDFE2",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { showRemoveKeyDialog = tokenState.provider }) {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = "Remove key",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showAddKeyDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("+ Add Key")
        }

        Spacer(Modifier.height(24.dp))

        // ── Model ──
        if (availableModels.isNotEmpty()) {
            Text(
                "MODEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = modelDisplay,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                ) {
                    // Group by provider
                    val grouped = availableModels.groupBy { it.first }
                    grouped.forEach { (provider, models) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        models.forEach { (prov, model) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.width(12.dp))
                                        Text(model.displayName)
                                        if (model.id == selectedModel) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.updateProvider(prov)
                                    viewModel.updateSelectedModel(model.id)
                                    modelDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Mask a token showing only the last 4 characters. */
private fun maskToken(token: String): String {
    if (token.length <= 4) return token
    return "${"*".repeat(minOf(token.length - 4, 12))}${token.takeLast(4)}"
}

/** Dialog for adding a new API key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeyDialog(
    configuredProviderIds: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (providerId: String, token: String) -> Unit,
) {
    // Show all providers, not just unconfigured ones (user might want to replace)
    val allProviders = AiProvider.entries.toList()
    // Default to first unconfigured provider, or first provider
    val defaultProvider = allProviders.firstOrNull { it.id !in configuredProviderIds } ?: allProviders.first()

    var selectedProvider by remember { mutableStateOf(defaultProvider) }
    var token by remember { mutableStateOf("") }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add API Key") },
        text = {
            Column {
                // Provider picker
                ExposedDropdownMenuBox(
                    expanded = providerDropdownExpanded,
                    onExpandedChange = { providerDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                    )
                    ExposedDropdownMenu(
                        expanded = providerDropdownExpanded,
                        onDismissRequest = { providerDropdownExpanded = false },
                    ) {
                        allProviders.forEach { provider ->
                            val alreadyConfigured = provider.id in configuredProviderIds
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(provider.displayName)
                                        if (alreadyConfigured) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "(has key)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedProvider = provider
                                    providerDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Hint text
                Text(
                    text = selectedProvider.tokenHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                // Token field
                TokenField(
                    token = token,
                    hint = "Paste token here",
                    label = "Token",
                    onTokenChange = { token = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selectedProvider.id, token) },
                enabled = token.isNotBlank(),
            ) {
                Text("Add Key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}


// ── Connectors Page ─────────────────────────────────────────────────────

@Composable
private fun ConnectorsPage(
    viewModel: SettingsViewModel,
    connectorStatuses: List<Pair<ConnectorConfig, ConnectorStatus>>,
    oauthLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        connectorStatuses.forEach { (connector, status) ->
            ConnectorRow(
                connector = connector,
                status = status,
                onConnect = { conn ->
                    val intent = viewModel.startOAuthFlow(conn)
                    if (intent != null) {
                        oauthLauncher.launch(intent)
                    }
                },
                onDisconnect = { connId ->
                    viewModel.disconnectConnector(connId)
                },
                onSaveToken = { connId, token ->
                    viewModel.saveConnectorToken(connId, token)
                },
                onSaveClientId = { connId, clientId ->
                    viewModel.saveConnectorClientId(connId, clientId)
                },
                getClientId = { connId ->
                    viewModel.getConnectorClientId(connId)
                },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectorRow(
    connector: ConnectorConfig,
    status: ConnectorStatus,
    onConnect: (ConnectorConfig) -> Unit,
    onDisconnect: (String) -> Unit,
    onSaveToken: (String, String) -> Unit,
    onSaveClientId: (String, String) -> Unit,
    getClientId: (String) -> String,
) {
    var manualToken by rememberSaveable(connector.id) { mutableStateOf("") }
    var clientId by rememberSaveable(connector.id) { mutableStateOf(getClientId(connector.id)) }
    var expanded by rememberSaveable(connector.id) { mutableStateOf(false) }

    val statusText = when (status) {
        ConnectorStatus.CONNECTED -> "Connected"
        ConnectorStatus.EXPIRED -> "Expired"
        ConnectorStatus.NOT_CONNECTED -> "Not connected"
    }
    val statusColor = when (status) {
        ConnectorStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectorStatus.EXPIRED -> MaterialTheme.colorScheme.error
        ConnectorStatus.NOT_CONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        ListItem(
            headlineContent = {
                Text(connector.name, fontWeight = FontWeight.Medium)
            },
            supportingContent = {
                Column {
                    Text(
                        connector.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            leadingContent = {
                Text(connector.icon, style = MaterialTheme.typography.titleLarge)
            },
            trailingContent = {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                when {
                    status == ConnectorStatus.CONNECTED -> {
                        OutlinedButton(
                            onClick = { onDisconnect(connector.id) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Disconnect")
                        }
                    }
                    connector.authType == ConnectorAuthType.OAUTH2_PKCE -> {
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = {
                                clientId = it
                                onSaveClientId(connector.id, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Client ID") },
                            placeholder = { Text("Your OAuth Client ID") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onConnect(connector) },
                            enabled = clientId.isNotBlank(),
                        ) {
                            Text("Connect")
                        }
                    }
                    connector.authType == ConnectorAuthType.API_KEY ||
                    connector.authType == ConnectorAuthType.BOT_TOKEN -> {
                        val label = if (connector.authType == ConnectorAuthType.API_KEY) "API Key" else "Bot Token"
                        OutlinedTextField(
                            value = manualToken,
                            onValueChange = { manualToken = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(label) },
                            placeholder = { Text("Enter your $label") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onSaveToken(connector.id, manualToken)
                                manualToken = ""
                            },
                            enabled = manualToken.isNotBlank(),
                        ) {
                            Text("Save")
                        }
                    }
                    connector.authType == ConnectorAuthType.GOOGLE_SIGNIN -> {
                        Text(
                            text = "Google Sign-In integration coming soon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Permissions Page ────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsPage(
    viewModel: SettingsViewModel,
    permissionMode: PermissionManager.PermissionMode,
    allowedTools: Set<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            "Permission Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PermissionManager.PermissionMode.entries.forEach { mode ->
                FilterChip(
                    selected = permissionMode == mode,
                    onClick = { viewModel.setPermissionMode(mode) },
                    label = { Text(mode.displayName) },
                    leadingIcon = if (permissionMode == mode) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Mode description
        val modeDescription = when (permissionMode) {
            PermissionManager.PermissionMode.DEFAULT -> "You will be asked to confirm dangerous actions like sending SMS, making calls, or modifying files."
            PermissionManager.PermissionMode.ALLOWLIST -> "Only selected tools below will be auto-approved. All others require confirmation."
            PermissionManager.PermissionMode.BYPASS_ALL -> "All tool actions will be auto-approved without confirmation."
        }
        Text(
            text = modeDescription,
            style = MaterialTheme.typography.bodySmall,
            color = if (permissionMode == PermissionManager.PermissionMode.BYPASS_ALL)
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Tool allowlist
        AnimatedVisibility(
            visible = permissionMode == PermissionManager.PermissionMode.ALLOWLIST,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "TOOL ALLOWLIST",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                viewModel.allToolNames.forEach { toolName ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = toolName.replace("_", " ")
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = toolName in allowedTools,
                            onCheckedChange = { viewModel.toggleToolAllowed(toolName, it) },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Device Page ─────────────────────────────────────────────────────────

@Composable
private fun DevicePage(
    viewModel: SettingsViewModel,
    deviceName: String,
) {
    val context = LocalContext.current
    val accessibilityEnabled = ClawAccessibilityService.instance != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device name") },
            placeholder = { Text("My Android Phone") },
            singleLine = true,
        )

        Spacer(Modifier.height(24.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Accessibility Service",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (accessibilityEnabled) "Enabled — MobileClaw can control your device"
                        else "Disabled — required for UI automation",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                if (!accessibilityEnabled) {
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                },
                            )
                        },
                    ) {
                        Text("Enable")
                    }
                } else {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Enabled",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Data & Storage Page ─────────────────────────────────────────────────

@Composable
private fun DataPage(
    onClearHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            "STORAGE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Conversation History",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Remove all saved conversations and messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onClearHistory,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(imageVector = Icons.Filled.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Conversation History")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── About Page ──────────────────────────────────────────────────────────

@Composable
private fun AboutPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            "\uD83E\uDD16",
            style = MaterialTheme.typography.displayLarge,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "MobileClaw",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            "by Affiora",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(32.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Android AI Agent",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "MobileClaw is an AI-powered agent that can control your Android device, automate tasks, and integrate with your favorite services.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Reusable components ─────────────────────────────────────────────────

@Composable
private fun TokenField(
    token: String,
    hint: String,
    label: String? = null,
    onTokenChange: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        modifier = Modifier.fillMaxWidth(),
        label = if (label != null) {
            { Text(label) }
        } else null,
        placeholder = { Text(hint, maxLines = 1) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (token.isNotBlank()) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Configured",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.Visibility
                        else Icons.Filled.VisibilityOff,
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            }
        },
    )
}
