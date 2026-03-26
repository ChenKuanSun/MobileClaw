package ai.affiora.mobileclaw.ui.cron

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    viewModel: CronViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedules", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add schedule",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { innerPadding ->
        if (uiState.schedules.isEmpty()) {
            EmptyScheduleState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(
                    items = uiState.schedules,
                    key = { it.name },
                ) { schedule ->
                    SwipeToDeleteScheduleItem(
                        schedule = schedule,
                        onTap = { viewModel.showEditDialog(schedule) },
                        onToggle = { enabled -> viewModel.toggleSchedule(schedule.name, enabled) },
                        onDelete = { viewModel.deleteSchedule(schedule.name) },
                    )
                }
            }
        }

        if (uiState.showAddDialog) {
            AddEditScheduleDialog(
                state = uiState,
                onDismiss = { viewModel.dismissDialog() },
                onSave = { viewModel.saveSchedule() },
                onNameChange = { viewModel.updateDialogName(it) },
                onPromptChange = { viewModel.updateDialogPrompt(it) },
                onIntervalChange = { viewModel.updateDialogInterval(it) },
                onCustomHoursChange = { viewModel.updateDialogCustomMinutes(it) },
            )
        }
    }
}

@Composable
private fun EmptyScheduleState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = "No scheduled tasks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap + to create a recurring task",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteScheduleItem(
    schedule: ScheduleItemUi,
    onTap: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                label = "swipe_bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ScheduleItemCard(
            schedule = schedule,
            onTap = onTap,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun ScheduleItemCard(
    schedule: ScheduleItemUi,
    onTap: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = schedule.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatInterval(schedule.intervalMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (schedule.nextRunTime > 0) {
                        Text(
                            text = " \u2022 Next: ${formatTimestamp(schedule.nextRunTime)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditScheduleDialog(
    state: CronUiState,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onIntervalChange: (ScheduleInterval) -> Unit,
    onCustomHoursChange: (String) -> Unit,
) {
    val isEditing = state.editingSchedule != null
    val title = if (isEditing) "Edit Schedule" else "New Schedule"
    val canSave = state.dialogName.isNotBlank() && state.dialogPrompt.isNotBlank()

    var intervalExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.dialogName,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("Morning briefing") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.dialogPrompt,
                    onValueChange = onPromptChange,
                    label = { Text("Prompt") },
                    placeholder = { Text("What should the AI do?") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = intervalExpanded,
                    onExpandedChange = { intervalExpanded = it },
                ) {
                    OutlinedTextField(
                        value = state.dialogInterval.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Interval") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                    )
                    ExposedDropdownMenu(
                        expanded = intervalExpanded,
                        onDismissRequest = { intervalExpanded = false },
                    ) {
                        ScheduleInterval.entries.forEach { interval ->
                            DropdownMenuItem(
                                text = { Text(interval.label) },
                                onClick = {
                                    onIntervalChange(interval)
                                    intervalExpanded = false
                                },
                            )
                        }
                    }
                }

                if (state.dialogInterval == ScheduleInterval.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.dialogCustomMinutes,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() } && value.length <= 4) {
                                onCustomHoursChange(value)
                            }
                        },
                        label = { Text("Interval (hours)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = canSave,
            ) {
                Text(if (isEditing) "Update" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatInterval(hours: Long): String = when (hours) {
    1L -> "Hourly"
    4L -> "Every 4 hours"
    8L -> "Every 8 hours"
    24L -> "Daily"
    168L -> "Weekly"
    else -> "Every ${hours}h"
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return "Unknown"
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(Date(millis))
}
