package ai.affiora.mobileclaw.ui.skills

import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.skills.Skill
import ai.affiora.mobileclaw.skills.SkillInstaller
import ai.affiora.mobileclaw.skills.SkillsManager
import ai.affiora.mobileclaw.skills.SkillsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SkillsUiState(
    val groupedSkills: Map<String, List<SkillUiItem>> = emptyMap(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
)

data class SkillUiItem(
    val skill: Skill,
    val isEnabled: Boolean,
)

sealed class InstallState {
    data object Downloading : InstallState()
    data class Scanning(val content: String) : InstallState()
    data class Preview(
        val content: String,
        val scanResult: SkillInstaller.ScanResult,
    ) : InstallState()
    data class Installing(val skillId: String) : InstallState()
    data class Installed(val skillId: String) : InstallState()
    data class Error(val message: String) : InstallState()
}

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillsRepository: SkillsRepository,
    private val skillsManager: SkillsManager,
    private val userPreferences: UserPreferences,
    private val skillInstaller: SkillInstaller,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val refreshTrigger = MutableStateFlow(0L)

    private val _installState = MutableStateFlow<InstallState?>(null)
    val installState: StateFlow<InstallState?> = _installState.asStateFlow()

    /** Holds the downloaded content pending user confirmation. */
    private var pendingContent: String? = null
    private var pendingUrl: String? = null

    val uiState: StateFlow<SkillsUiState> = combine(
        searchQuery,
        userPreferences.activeSkillIds,
        refreshTrigger,
    ) { query, activeIds, _ ->
        val grouped = skillsRepository.getSkillsGrouped()

        val filtered = if (query.isBlank()) {
            grouped
        } else {
            val lowerQuery = query.lowercase()
            grouped.mapValues { (_, skills) ->
                skills.filter { skill ->
                    skill.name.lowercase().contains(lowerQuery) ||
                        skill.description.lowercase().contains(lowerQuery)
                }
            }.filterValues { it.isNotEmpty() }
        }

        val uiGrouped = filtered.mapValues { (_, skills) ->
            skills.map { skill ->
                SkillUiItem(
                    skill = skill,
                    isEnabled = skill.id in activeIds,
                )
            }
        }

        SkillsUiState(
            groupedSkills = uiGrouped,
            searchQuery = query,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SkillsUiState(),
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onToggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                skillsManager.enableSkill(skillId)
            } else {
                skillsManager.disableSkill(skillId)
            }
            refreshTrigger.value = System.currentTimeMillis()
        }
    }

    fun installFromUrl(url: String) {
        pendingUrl = url
        _installState.value = InstallState.Downloading

        viewModelScope.launch {
            val result = skillInstaller.downloadSkill(url)
            if (result.isFailure) {
                _installState.value = InstallState.Error(
                    "Download failed: ${result.exceptionOrNull()?.message}",
                )
                return@launch
            }

            val content = result.getOrThrow()
            _installState.value = InstallState.Scanning(content)

            val scan = skillInstaller.scanContent(content)

            if (scan.riskLevel == SkillInstaller.RiskLevel.BLOCKED) {
                _installState.value = InstallState.Error(
                    "BLOCKED: ${scan.blockedReasons.joinToString("; ")}",
                )
                return@launch
            }

            pendingContent = content
            _installState.value = InstallState.Preview(
                content = content,
                scanResult = scan,
            )
        }
    }

    fun confirmInstall() {
        val content = pendingContent ?: return
        val url = pendingUrl ?: return

        // Derive skill ID from URL: last path segment minus extension
        val skillId = url.trimEnd('/')
            .substringAfterLast('/')
            .removeSuffix(".md")
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "-")
            .ifBlank { "imported-skill-${System.currentTimeMillis()}" }

        _installState.value = InstallState.Installing(skillId)

        viewModelScope.launch {
            val saved = skillInstaller.saveSkill(skillId, content)
            if (saved) {
                skillsManager.enableSkill(skillId)
                _installState.value = InstallState.Installed(skillId)
                refreshTrigger.value = System.currentTimeMillis()
            } else {
                _installState.value = InstallState.Error("Failed to save skill to disk.")
            }
            pendingContent = null
            pendingUrl = null
        }
    }

    fun dismissInstall() {
        _installState.value = null
        pendingContent = null
        pendingUrl = null
    }
}
