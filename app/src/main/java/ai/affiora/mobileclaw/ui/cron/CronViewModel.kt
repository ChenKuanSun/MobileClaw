package ai.affiora.mobileclaw.ui.cron

import ai.affiora.mobileclaw.agent.ScheduleEngine
import ai.affiora.mobileclaw.agent.ScheduleInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ScheduleInterval(val label: String, val hours: Long) {
    HOURLY("Every hour", 1),
    EVERY_4_HOURS("Every 4 hours", 4),
    EVERY_8_HOURS("Every 8 hours", 8),
    DAILY("Daily", 24),
    WEEKLY("Weekly", 168),
    CUSTOM("Custom", 0),
}

data class CronUiState(
    val schedules: List<ScheduleItemUi> = emptyList(),
    val showAddDialog: Boolean = false,
    val editingSchedule: ScheduleItemUi? = null,
    val dialogName: String = "",
    val dialogPrompt: String = "",
    val dialogInterval: ScheduleInterval = ScheduleInterval.DAILY,
    val dialogCustomHours: String = "24",
    val dialogHour: Int = 9,
    val dialogMinute: Int = 0,
)

data class ScheduleItemUi(
    val name: String,
    val prompt: String,
    val intervalHours: Long,
    val nextRunTime: Long,
    val enabled: Boolean,
)

@HiltViewModel
class CronViewModel @Inject constructor(
    private val scheduleEngine: ScheduleEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CronUiState())
    val uiState: StateFlow<CronUiState> = _uiState.asStateFlow()

    // Track enabled/disabled state locally (ScheduleEngine doesn't have toggle)
    private val disabledSchedules = mutableSetOf<String>()

    init {
        refreshSchedules()
    }

    fun refreshSchedules() {
        val schedules = scheduleEngine.listSchedules().map { info ->
            ScheduleItemUi(
                name = info.name,
                prompt = info.skillAction,
                intervalHours = info.intervalHours,
                nextRunTime = info.nextRunTime,
                enabled = info.name !in disabledSchedules,
            )
        }
        _uiState.update { it.copy(schedules = schedules) }
    }

    fun showAddDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = true,
                editingSchedule = null,
                dialogName = "",
                dialogPrompt = "",
                dialogInterval = ScheduleInterval.DAILY,
                dialogCustomHours = "24",
                dialogHour = 9,
                dialogMinute = 0,
            )
        }
    }

    fun showEditDialog(schedule: ScheduleItemUi) {
        val interval = ScheduleInterval.entries.find { it.hours == schedule.intervalHours }
            ?: ScheduleInterval.CUSTOM
        _uiState.update {
            it.copy(
                showAddDialog = true,
                editingSchedule = schedule,
                dialogName = schedule.name,
                dialogPrompt = schedule.prompt,
                dialogInterval = interval,
                dialogCustomHours = if (interval == ScheduleInterval.CUSTOM) {
                    schedule.intervalHours.toString()
                } else {
                    schedule.intervalHours.toString()
                },
                dialogHour = 9,
                dialogMinute = 0,
            )
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showAddDialog = false, editingSchedule = null) }
    }

    fun updateDialogName(name: String) {
        _uiState.update { it.copy(dialogName = name) }
    }

    fun updateDialogPrompt(prompt: String) {
        _uiState.update { it.copy(dialogPrompt = prompt) }
    }

    fun updateDialogInterval(interval: ScheduleInterval) {
        _uiState.update { it.copy(dialogInterval = interval) }
    }

    fun updateDialogCustomHours(hours: String) {
        _uiState.update { it.copy(dialogCustomHours = hours) }
    }

    fun updateDialogHour(hour: Int) {
        _uiState.update { it.copy(dialogHour = hour) }
    }

    fun updateDialogMinute(minute: Int) {
        _uiState.update { it.copy(dialogMinute = minute) }
    }

    fun saveSchedule() {
        val state = _uiState.value
        val name = state.dialogName.trim()
        val prompt = state.dialogPrompt.trim()
        if (name.isBlank() || prompt.isBlank()) return

        val hours = when (state.dialogInterval) {
            ScheduleInterval.CUSTOM -> state.dialogCustomHours.toLongOrNull() ?: 24L
            else -> state.dialogInterval.hours
        }.coerceAtLeast(1L)

        // If editing, cancel the old schedule first
        val editing = state.editingSchedule
        if (editing != null && editing.name != name) {
            scheduleEngine.cancelSchedule(editing.name)
            disabledSchedules.remove(editing.name)
        }

        scheduleEngine.scheduleRecurring(name, hours, prompt)
        disabledSchedules.remove(name)

        dismissDialog()
        refreshSchedules()
    }

    fun deleteSchedule(name: String) {
        scheduleEngine.cancelSchedule(name)
        disabledSchedules.remove(name)
        refreshSchedules()
    }

    fun toggleSchedule(name: String, enabled: Boolean) {
        if (enabled) {
            // Re-enable: find the schedule info and re-register
            val schedule = _uiState.value.schedules.find { it.name == name } ?: return
            disabledSchedules.remove(name)
            scheduleEngine.scheduleRecurring(name, schedule.intervalHours, schedule.prompt)
        } else {
            // Disable: cancel from WorkManager but keep in our list
            disabledSchedules.add(name)
            scheduleEngine.cancelSchedule(name)
        }
        refreshSchedules()
    }
}
