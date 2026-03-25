package ai.affiora.mobileclaw.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import ai.affiora.mobileclaw.BuildConfig
import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.agent.PermissionManager
import ai.affiora.mobileclaw.connectors.ConnectorAuthType
import ai.affiora.mobileclaw.connectors.ConnectorConfig
import ai.affiora.mobileclaw.connectors.ConnectorStatus
import ai.affiora.mobileclaw.tools.ClawAccessibilityService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

private data class ProviderGroup(
    val label: String,
    val providers: List<AiProvider>,
)

private val providerGroups = listOf(
    ProviderGroup("Anthropic", listOf(AiProvider.ANTHROPIC, AiProvider.ANTHROPIC_TOKEN)),
    ProviderGroup("OpenAI", listOf(AiProvider.OPENAI, AiProvider.OPENAI_CODEX)),
    ProviderGroup("Google", listOf(AiProvider.GOOGLE)),
    ProviderGroup(
        "Others",
        listOf(
            AiProvider.OPENROUTER,
            AiProvider.MISTRAL,
            AiProvider.TOGETHER,
            AiProvider.GROQ,
            AiProvider.XAI,
            AiProvider.DEEPSEEK,
            AiProvider.FIREWORKS,
        ),
    ),
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

    var showClearDialog by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var otherTokensExpanded by rememberSaveable { mutableStateOf(false) }
    var connectorsExpanded by rememberSaveable { mutableStateOf(true) }

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
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── AI Provider Card ────────────────────────────────────────
            SettingsCard {
                CardTitle("AI Provider")
                Spacer(Modifier.height(8.dp))

                // Provider dropdown
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
                        providerGroups.forEach { group ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = group.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                            group.providers.forEach { provider ->
                                val tokenState = providerTokens.firstOrNull { it.provider == provider }
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Spacer(Modifier.width(12.dp))
                                            Text(provider.displayName)
                                            if (tokenState?.hasToken == true) {
                                                Spacer(Modifier.width(8.dp))
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = "Configured",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateProvider(provider)
                                        providerDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Token for active provider
                val activeToken = providerTokens.firstOrNull { it.provider == selectedProvider }
                TokenField(
                    token = activeToken?.token ?: "",
                    hint = selectedProvider.tokenHint,
                    label = "${selectedProvider.displayName} Token",
                    onTokenChange = { viewModel.updateTokenForProvider(selectedProvider.id, it) },
                )

                Spacer(Modifier.height(12.dp))

                // Model dropdown
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it },
                ) {
                    val displayModel = selectedProvider.models
                        .firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
                    OutlinedTextField(
                        value = displayModel,
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
                        selectedProvider.models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    viewModel.updateSelectedModel(model.id)
                                    modelDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Other Provider Tokens Card ──────────────────────────────
            val otherTokens = providerTokens.filter { it.provider != selectedProvider }
            val configuredCount = otherTokens.count { it.hasToken }
            val totalCount = otherTokens.size

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { otherTokensExpanded = !otherTokensExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        CardTitle("Other Provider Tokens")
                        Text(
                            text = "$configuredCount of $totalCount configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (otherTokensExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (otherTokensExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = otherTokensExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        otherTokens.forEach { tokenState ->
                            val label = buildString {
                                append(tokenState.provider.displayName)
                                if (tokenState.hasToken) append(" \u2713")
                            }
                            TokenField(
                                token = tokenState.token,
                                hint = tokenState.provider.tokenHint,
                                label = label,
                                onTokenChange = { viewModel.updateTokenForProvider(tokenState.provider.id, it) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Connectors Card ─────────────────────────────────────────
            SettingsCard {
                val connectedCount = connectorStatuses.count { it.second == ConnectorStatus.CONNECTED }
                val totalCount = connectorStatuses.size

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { connectorsExpanded = !connectorsExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        CardTitle("Connectors")
                        Text(
                            text = "$connectedCount of $totalCount connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (connectorsExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (connectorsExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = connectorsExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
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
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Permissions Card ────────────────────────────────────────
            SettingsCard {
                CardTitle("Permissions")
                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionManager.PermissionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = permissionMode == mode,
                            onClick = {
                                viewModel.setPermissionMode(mode)
                            },
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

                // Bypass warning
                AnimatedVisibility(
                    visible = permissionMode == PermissionManager.PermissionMode.BYPASS_ALL,
                ) {
                    Text(
                        text = "Warning: All tool actions (SMS, calls, file ops, app interactions) will be auto-approved without confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // Tool allowlist
                AnimatedVisibility(
                    visible = permissionMode == PermissionManager.PermissionMode.ALLOWLIST,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Tool Allowlist",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        viewModel.allToolNames.forEach { toolName ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    text = toolName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = toolName in allowedTools,
                                    onCheckedChange = { viewModel.toggleToolAllowed(toolName, it) },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Device Card ─────────────────────────────────────────────
            SettingsCard {
                CardTitle("Device")
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { viewModel.updateDeviceName(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Device name") },
                    placeholder = { Text("My Android Phone") },
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))

                val accessibilityEnabled = ClawAccessibilityService.instance != null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accessibility Service",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (accessibilityEnabled) "Enabled" else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!accessibilityEnabled) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            },
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Data Card ───────────────────────────────────────────────
            SettingsCard {
                CardTitle("Data")
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { showClearDialog = true },
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

            Spacer(Modifier.height(24.dp))

            // ── Footer ──────────────────────────────────────────────────
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                text = "MobileClaw by Affiora",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Reusable components ─────────────────────────────────────────────────

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun CardTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = connector.icon,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connector.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = connector.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                ConnectorStatus.CONNECTED -> {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                ConnectorStatus.EXPIRED -> {
                    Text(
                        text = "Expired",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                ConnectorStatus.NOT_CONNECTED -> {
                    Text(
                        text = "Not Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 32.dp, top = 8.dp)) {
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
                        // Client ID field
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
