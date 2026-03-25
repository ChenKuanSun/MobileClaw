package ai.affiora.mobileclaw.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AiProviderTest {

    @Test
    fun `LOCAL_GEMMA isLocal returns true`() {
        assertThat(AiProvider.LOCAL_GEMMA.isLocal).isTrue()
    }

    @Test
    fun `cloud providers isLocal returns false`() {
        assertThat(AiProvider.ANTHROPIC.isLocal).isFalse()
        assertThat(AiProvider.OPENAI.isLocal).isFalse()
        assertThat(AiProvider.GOOGLE.isLocal).isFalse()
    }

    @Test
    fun `LOCAL_GEMMA is not Anthropic`() {
        assertThat(AiProvider.LOCAL_GEMMA.isAnthropic).isFalse()
    }

    @Test
    fun `LOCAL_GEMMA is not OpenAI compatible`() {
        assertThat(AiProvider.LOCAL_GEMMA.isOpenAiCompatible).isFalse()
    }

    @Test
    fun `LOCAL_GEMMA has E2B and E4B models`() {
        val modelIds = AiProvider.LOCAL_GEMMA.models.map { it.id }
        assertThat(modelIds).containsExactly("gemma-4-e2b", "gemma-4-e4b")
    }

    @Test
    fun `fromId returns LOCAL_GEMMA for local-gemma`() {
        assertThat(AiProvider.fromId("local-gemma")).isEqualTo(AiProvider.LOCAL_GEMMA)
    }

    @Test
    fun `fromId returns ANTHROPIC for unknown id`() {
        assertThat(AiProvider.fromId("unknown")).isEqualTo(AiProvider.ANTHROPIC)
    }

    @Test
    fun `LOCAL_GEMMA tokenHint indicates no key needed`() {
        assertThat(AiProvider.LOCAL_GEMMA.tokenHint.lowercase()).contains("no api key")
    }
}
