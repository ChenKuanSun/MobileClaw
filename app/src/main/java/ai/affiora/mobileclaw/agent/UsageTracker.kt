package ai.affiora.mobileclaw.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0,
)

@Singleton
class UsageTracker @Inject constructor() {

    private val _sessionUsage = MutableStateFlow(TokenUsage())
    val sessionUsage: StateFlow<TokenUsage> = _sessionUsage.asStateFlow()

    fun recordUsage(inputTokens: Int, outputTokens: Int, model: String) {
        _sessionUsage.update { current ->
            val newInput = current.inputTokens + inputTokens
            val newOutput = current.outputTokens + outputTokens
            val newTotal = newInput + newOutput
            val newCost = current.estimatedCostUsd + calculateCost(inputTokens, outputTokens, model)
            TokenUsage(
                inputTokens = newInput,
                outputTokens = newOutput,
                totalTokens = newTotal,
                estimatedCostUsd = newCost,
            )
        }
    }

    fun resetSession() {
        _sessionUsage.value = TokenUsage()
    }

    fun estimateCost(): Double = _sessionUsage.value.estimatedCostUsd

    private fun calculateCost(inputTokens: Int, outputTokens: Int, model: String): Double {
        val (inputPricePerMillion, outputPricePerMillion) = when {
            // Anthropic models
            model.contains("opus") -> 15.0 to 75.0
            model.contains("sonnet") -> 3.0 to 15.0
            model.contains("haiku") -> 0.80 to 4.0
            // OpenAI models
            model.contains("gpt-4o-mini") -> 0.15 to 0.60
            model.contains("gpt-4o") -> 2.50 to 10.0
            model.contains("o3-mini") -> 1.10 to 4.40
            // Google models
            model.contains("gemini-2.5-pro") -> 1.25 to 10.0
            model.contains("gemini-2.5-flash") -> 0.15 to 0.60
            // Grok
            model.contains("grok-3-mini") -> 0.30 to 0.50
            model.contains("grok-3") -> 3.0 to 15.0
            // DeepSeek
            model.contains("deepseek") -> 0.27 to 1.10
            // Default fallback
            else -> 3.0 to 15.0
        }
        return (inputTokens.toDouble() / 1_000_000 * inputPricePerMillion) +
            (outputTokens.toDouble() / 1_000_000 * outputPricePerMillion)
    }
}
