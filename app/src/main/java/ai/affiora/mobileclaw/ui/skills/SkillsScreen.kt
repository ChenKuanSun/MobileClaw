package ai.affiora.mobileclaw.ui.skills

import ai.affiora.mobileclaw.skills.Skill
import ai.affiora.mobileclaw.skills.SkillInstaller
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel = hiltViewModel(),
    onNavigateToChat: (() -> Unit)? = null,
) {
    val localContext = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    var showInstallDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }

    // Install dialog
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            title = { Text("Install Skill from URL") },
            text = {
                OutlinedTextField(
                    value = installUrl,
                    onValueChange = { installUrl = it },
                    label = { Text("Skill URL") },
                    placeholder = { Text("https://clawhub.com/skills/.../SKILL.md") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (installUrl.isNotBlank()) {
                            viewModel.installFromUrl(installUrl.trim())
                            showInstallDialog = false
                            installUrl = ""
                        }
                    },
                    enabled = installUrl.isNotBlank(),
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Install state dialogs
    when (val currentInstallState = installState) {
        is InstallState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading...") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Fetching skill content...")
                    }
                },
                confirmButton = {},
            )
        }

        is InstallState.Scanning -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Scanning...") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Running security scan...")
                    }
                },
                confirmButton = {},
            )
        }

        is InstallState.Preview -> {
            InstallPreviewDialog(
                content = currentInstallState.content,
                scanResult = currentInstallState.scanResult,
                onConfirm = { viewModel.confirmInstall() },
                onDismiss = { viewModel.dismissInstall() },
            )
        }

        is InstallState.Installing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Installing...") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Saving skill '${currentInstallState.skillId}'...")
                    }
                },
                confirmButton = {},
            )
        }

        is InstallState.Installed -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissInstall() },
                title = { Text("Installed") },
                text = { Text("Skill '${currentInstallState.skillId}' installed and enabled.") },
                confirmButton = {
                    Button(onClick = { viewModel.dismissInstall() }) {
                        Text("OK")
                    }
                },
            )
        }

        is InstallState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissInstall() },
                title = { Text("Install Failed") },
                text = { Text(currentInstallState.message) },
                confirmButton = {
                    Button(onClick = { viewModel.dismissInstall() }) {
                        Text("OK")
                    }
                },
            )
        }

        null -> { /* no install in progress */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills Library") },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add skill",
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Create with AI") },
                                onClick = {
                                    showAddMenu = false
                                    onNavigateToChat?.invoke()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Install from URL") },
                                onClick = {
                                    showAddMenu = false
                                    showInstallDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Browse ClawHub") },
                                onClick = {
                                    showAddMenu = false
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://clawhub.com"),
                                        )
                                        localContext.startActivity(intent)
                                    } catch (_: Exception) { }
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchBar(
                query = state.searchQuery,
                onQueryChanged = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((category, skills) in state.groupedSkills) {
                        item(key = "header_$category") {
                            CategoryHeader(category = category)
                        }
                        items(
                            items = skills,
                            key = { it.skill.id },
                        ) { item ->
                            SkillCard(
                                skill = item.skill,
                                isEnabled = item.isEnabled,
                                onToggle = { enabled ->
                                    viewModel.onToggleSkill(item.skill.id, enabled)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallPreviewDialog(
    content: String,
    scanResult: SkillInstaller.ScanResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review Skill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Risk level badge
                RiskBadge(riskLevel = scanResult.riskLevel)

                // Warnings
                if (scanResult.warnings.isNotEmpty()) {
                    Text(
                        text = "Warnings:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    for (warning in scanResult.warnings) {
                        Text(
                            text = "- $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Content preview
                Text(
                    text = "Content preview:",
                    style = MaterialTheme.typography.titleSmall,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = content.take(1000) + if (content.length > 1000) "\n..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 30,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Install")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RiskBadge(riskLevel: SkillInstaller.RiskLevel) {
    val (label, color) = when (riskLevel) {
        SkillInstaller.RiskLevel.LOW -> "LOW RISK" to MaterialTheme.colorScheme.primary
        SkillInstaller.RiskLevel.MEDIUM -> "MEDIUM RISK" to Color(0xFFFF9800)
        SkillInstaller.RiskLevel.HIGH -> "HIGH RISK" to MaterialTheme.colorScheme.error
        SkillInstaller.RiskLevel.BLOCKED -> "BLOCKED" to MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier,
        placeholder = { Text("Search skills...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
            )
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun CategoryHeader(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillCard(
    skill: Skill,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "v${skill.version} by ${skill.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (skill.toolsRequired.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (tool in skill.toolsRequired) {
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = tool,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
