package ai.affiora.mobileclaw.ui.onboarding

import ai.affiora.mobileclaw.agent.AiProvider
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.skills.Skill
import ai.affiora.mobileclaw.skills.SkillsManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val currentStep: Int = 0,
    val selectedProvider: AiProvider = AiProvider.ANTHROPIC,
    val apiKey: String = "",
    val apiKeyError: String? = null,
    val availableSkills: List<SkillSelectionItem> = emptyList(),
    val isCompleting: Boolean = false,
)

data class SkillSelectionItem(
    val skill: Skill,
    val isSelected: Boolean,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
    private val skillsManager: SkillsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    companion object {
        const val TOTAL_STEPS = 5
        private val BUILT_IN_SKILL_IDS = setOf("phone-basics", "morning-routine")
    }

    init {
        loadAvailableSkills()
    }

    private fun loadAvailableSkills() {
        val allSkills = skillsManager.getAllSkills()
        val builtInSkills = allSkills.filter { it.id in BUILT_IN_SKILL_IDS }
        val skillItems = if (builtInSkills.isNotEmpty()) {
            builtInSkills.map { skill ->
                SkillSelectionItem(skill = skill, isSelected = true)
            }
        } else {
            BUILT_IN_SKILL_IDS.map { id ->
                SkillSelectionItem(
                    skill = Skill(
                        id = id,
                        name = id.replace("-", " ").replaceFirstChar { it.uppercase() },
                        description = when (id) {
                            "phone-basics" -> "Basic phone operations: calls, messages, contacts"
                            "morning-routine" -> "Automated morning briefing and routine tasks"
                            else -> ""
                        },
                        version = "1.0",
                        author = "MobileClaw",
                        toolsRequired = emptyList(),
                        content = "",
                    ),
                    isSelected = true,
                )
            }
        }
        _uiState.update { it.copy(availableSkills = skillItems) }
    }

    fun onProviderChanged(provider: AiProvider) {
        _uiState.update {
            it.copy(
                selectedProvider = provider,
                apiKey = "",
                apiKeyError = null,
            )
        }
    }

    fun onApiKeyChanged(key: String) {
        _uiState.update {
            it.copy(
                apiKey = key,
                apiKeyError = null,
            )
        }
    }

    fun onToggleSkill(skillId: String) {
        _uiState.update { state ->
            state.copy(
                availableSkills = state.availableSkills.map { item ->
                    if (item.skill.id == skillId) {
                        item.copy(isSelected = !item.isSelected)
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun goToStep(step: Int) {
        if (step in 0 until TOTAL_STEPS) {
            _uiState.update { it.copy(currentStep = step) }
        }
    }

    fun nextStep(): Boolean {
        val state = _uiState.value
        // Validate API key on step 1 -> 2 transition (skip for local providers)
        if (state.currentStep == 1 && !state.selectedProvider.isLocal) {
            if (state.apiKey.isBlank()) {
                _uiState.update { it.copy(apiKeyError = "API key is required") }
                return false
            }
        }
        if (state.currentStep < TOTAL_STEPS - 1) {
            _uiState.update { it.copy(currentStep = state.currentStep + 1) }
            return true
        }
        return false
    }

    fun previousStep() {
        val state = _uiState.value
        if (state.currentStep > 0) {
            _uiState.update { it.copy(currentStep = state.currentStep - 1) }
        }
    }

    fun completeOnboarding(onComplete: () -> Unit) {
        val state = _uiState.value
        _uiState.update { it.copy(isCompleting = true) }

        viewModelScope.launch {
            val provider = state.selectedProvider
            if (!provider.isLocal) {
                userPreferences.setTokenForProvider(provider.id, state.apiKey)
            }
            userPreferences.setSelectedProvider(provider.id)
            userPreferences.setSelectedModel(provider.models.first().id)

            val selectedIds = state.availableSkills
                .filter { it.isSelected }
                .map { it.skill.id }
                .toSet()
            userPreferences.setActiveSkillIds(selectedIds)

            userPreferences.setOnboardingCompleted(true)

            onComplete()
        }
    }
}
