package ai.affiora.mobileclaw.agent

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LocalModelManagerTest {

    @Test
    fun `MODELS contains E2B and E4B`() {
        val ids = LocalModelManager.MODELS.map { it.id }
        assertThat(ids).containsExactly("gemma-4-e2b", "gemma-4-e4b")
    }

    @Test
    fun `getModelInfo returns correct model for known id`() {
        val e2b = LocalModelManager.getModelInfo("gemma-4-e2b")
        assertThat(e2b).isNotNull()
        assertThat(e2b!!.displayName).isEqualTo("Gemma 4 E2B")
        assertThat(e2b.requiredRamMb).isEqualTo(6_000)
    }

    @Test
    fun `getModelInfo returns null for unknown id`() {
        val unknown = LocalModelManager.getModelInfo("nonexistent")
        assertThat(unknown).isNull()
    }

    @Test
    fun `E2B requires less RAM than E4B`() {
        val e2b = LocalModelManager.getModelInfo("gemma-4-e2b")!!
        val e4b = LocalModelManager.getModelInfo("gemma-4-e4b")!!
        assertThat(e2b.requiredRamMb).isLessThan(e4b.requiredRamMb)
    }

    @Test
    fun `E2B file size is smaller than E4B`() {
        val e2b = LocalModelManager.getModelInfo("gemma-4-e2b")!!
        val e4b = LocalModelManager.getModelInfo("gemma-4-e4b")!!
        assertThat(e2b.fileSizeBytes).isLessThan(e4b.fileSizeBytes)
    }

    @Test
    fun `HuggingFace repo follows expected naming pattern`() {
        for (model in LocalModelManager.MODELS) {
            assertThat(model.huggingFaceRepo).startsWith("litert-community/")
            assertThat(model.fileName).endsWith(".litertlm")
        }
    }
}
