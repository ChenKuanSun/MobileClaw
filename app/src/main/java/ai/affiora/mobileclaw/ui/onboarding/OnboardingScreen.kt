package ai.affiora.mobileclaw.ui.onboarding

import ai.affiora.mobileclaw.R
import ai.affiora.mobileclaw.agent.AiProvider
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { OnboardingViewModel.TOTAL_STEPS })
    val coroutineScope = rememberCoroutineScope()

    // Sync pager with viewmodel state
    LaunchedEffect(state.currentStep) {
        if (pagerState.currentPage != state.currentStep) {
            pagerState.animateScrollToPage(state.currentStep)
        }
    }

    // Sync viewmodel with pager swipes
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.goToStep(page)
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Step indicator dots
            StepIndicator(
                totalSteps = OnboardingViewModel.TOTAL_STEPS,
                currentStep = state.currentStep,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 16.dp),
            )

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = false,
            ) { page ->
                when (page) {
                    0 -> WelcomePage(
                        onGetStarted = {
                            coroutineScope.launch {
                                viewModel.nextStep()
                            }
                        },
                    )
                    1 -> ApiKeyPage(
                        selectedProvider = state.selectedProvider,
                        apiKey = state.apiKey,
                        error = state.apiKeyError,
                        onProviderChanged = viewModel::onProviderChanged,
                        onApiKeyChanged = viewModel::onApiKeyChanged,
                        onNext = {
                            coroutineScope.launch {
                                viewModel.nextStep()
                            }
                        },
                    )
                    2 -> PermissionsPage(
                        onGrantPermissions = {
                            coroutineScope.launch {
                                viewModel.nextStep()
                            }
                        },
                    )
                    3 -> SkillSelectionPage(
                        skills = state.availableSkills,
                        onToggleSkill = viewModel::onToggleSkill,
                        onNext = {
                            coroutineScope.launch {
                                viewModel.nextStep()
                            }
                        },
                    )
                    4 -> CompletionPage(
                        isCompleting = state.isCompleting,
                        onStartChatting = {
                            viewModel.completeOnboarding(onComplete = onOnboardingComplete)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(
    totalSteps: Int,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val color by animateColorAsState(
                targetValue = if (index <= currentStep) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                label = "dot_color_$index",
            )
            Box(
                modifier = Modifier
                    .size(if (index == currentStep) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            if (index < totalSteps - 1) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

// ── Step 1: Welcome ─────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(
    onGetStarted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_get_started))
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

// ── Step 2: API Key ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyPage(
    selectedProvider: AiProvider,
    apiKey: String,
    error: String?,
    onProviderChanged: (AiProvider) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onNext: () -> Unit,
) {
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_connect_provider),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_api_key_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Provider dropdown
        ExposedDropdownMenuBox(
            expanded = providerDropdownExpanded,
            onExpandedChange = { providerDropdownExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedProvider.displayName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                label = { Text(stringResource(R.string.settings_provider)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
            )
            ExposedDropdownMenu(
                expanded = providerDropdownExpanded,
                onDismissRequest = { providerDropdownExpanded = false },
            ) {
                AiProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            onProviderChanged(provider)
                            providerDropdownExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Token input
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.onboarding_api_key_label)) },
            placeholder = { Text(selectedProvider.tokenHint) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else {
                null
            },
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotBlank(),
        ) {
            Text(stringResource(R.string.onboarding_next))
        }
    }
}

// ── Step 3: Permissions ─────────────────────────────────────────────────────

private data class PermissionInfo(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val permission: String,
)

private val dangerousPermissions: List<PermissionInfo> = buildList {
    add(PermissionInfo("SMS (Read)", "Read text messages", Icons.Default.Sms, Manifest.permission.READ_SMS))
    add(PermissionInfo("SMS (Send)", "Send text messages", Icons.Default.Sms, Manifest.permission.SEND_SMS))
    add(PermissionInfo("Call Log", "Read call history", Icons.Default.Phone, Manifest.permission.READ_CALL_LOG))
    add(PermissionInfo("Contacts", "Access your contacts", Icons.Default.Phone, Manifest.permission.READ_CONTACTS))
    add(PermissionInfo("Calendar (Read)", "Read calendar events", Icons.Default.Notifications, Manifest.permission.READ_CALENDAR))
    add(PermissionInfo("Calendar (Write)", "Create calendar events", Icons.Default.Notifications, Manifest.permission.WRITE_CALENDAR))
    add(PermissionInfo("Camera", "Take photos and videos", Icons.Default.Phone, Manifest.permission.CAMERA))
    add(PermissionInfo("Microphone", "Record audio", Icons.Default.Phone, Manifest.permission.RECORD_AUDIO))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionInfo("Photos & Media", "Access photos for AI analysis", Icons.Default.Phone, Manifest.permission.READ_MEDIA_IMAGES))
        add(PermissionInfo("Audio Files", "Access audio for transcription", Icons.Default.Phone, Manifest.permission.READ_MEDIA_AUDIO))
        add(PermissionInfo("Notifications", "Show task progress and alerts", Icons.Default.Notifications, Manifest.permission.POST_NOTIFICATIONS))
    }
}

@Composable
private fun PermissionsPage(
    onGrantPermissions: () -> Unit,
) {
    // Track grant results per permission
    var permissionResults by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var hasRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionResults = results
        hasRequested = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        dangerousPermissions.forEach { permission ->
            val granted = permissionResults[permission.permission]
            PermissionRow(permission = permission, granted = granted)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (hasRequested) {
            val grantedCount = permissionResults.count { it.value }
            val deniedCount = permissionResults.count { !it.value }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_permissions_result, grantedCount, deniedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (!hasRequested) {
            Button(
                onClick = {
                    val perms = dangerousPermissions.map { it.permission }.toTypedArray()
                    permissionLauncher.launch(perms)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text(stringResource(R.string.onboarding_grant_permissions))
            }
        }

        OutlinedButton(
            onClick = onGrantPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(if (hasRequested) stringResource(R.string.onboarding_continue) else stringResource(R.string.onboarding_skip))
        }
    }
}

@Composable
private fun PermissionRow(permission: PermissionInfo, granted: Boolean?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (granted) {
                true -> MaterialTheme.colorScheme.secondaryContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = permission.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted != null) {
                Icon(
                    imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = if (granted) "Granted" else "Denied",
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ── Step 4: Skill Selection ─────────────────────────────────────────────────

@Composable
private fun SkillSelectionPage(
    skills: List<SkillSelectionItem>,
    onToggleSkill: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.onboarding_choose_skills),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_skills_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = skills,
                key = { it.skill.id },
            ) { item ->
                SkillCheckboxCard(
                    item = item,
                    onToggle = { onToggleSkill(item.skill.id) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(stringResource(R.string.onboarding_next))
        }
    }
}

@Composable
private fun SkillCheckboxCard(
    item: SkillSelectionItem,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggle() },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.skill.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = item.skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Step 5: Completion ──────────────────────────────────────────────────────

@Composable
private fun CompletionPage(
    isCompleting: Boolean,
    onStartChatting: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.onboarding_ready),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_ready_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(48.dp))

        if (isCompleting) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        } else {
            Button(
                onClick = onStartChatting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_start_chatting))
            }
        }
    }
}
