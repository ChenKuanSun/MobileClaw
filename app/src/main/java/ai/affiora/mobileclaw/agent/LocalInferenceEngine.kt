package ai.affiora.mobileclaw.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.affiora.mobileclaw.data.model.ClaudeContent
import ai.affiora.mobileclaw.data.model.ClaudeMessage
import ai.affiora.mobileclaw.data.model.ClaudeResponse
import ai.affiora.mobileclaw.data.model.ContentBlock
import ai.affiora.mobileclaw.tools.AndroidTool
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device Gemma 4 inference via LiteRT-LM 0.10.0.
 *
 * Backend priority: NPU → GPU → CPU (by efficiency).
 * Crash-resilient: if a backend causes native SIGABRT, it's auto-blacklisted on next launch.
 *
 * Reference: google-ai-edge/gallery LlmChatModelHelper.kt
 */
@Singleton
class LocalInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "LocalInference"
        private const val BACKGROUND_RELEASE_DELAY_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "local_inference_backend"
        private const val KEY_ATTEMPTING = "attempting_backend"
        private const val KEY_BLACKLISTED = "blacklisted_backends"
        private const val KEY_LAST_GOOD = "last_good_backend"
        private val BACKEND_PRIORITY = listOf("NPU", "GPU", "CPU")
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null
    private var activeBackendName: String? = null
    private var toolProviders: List<ToolProvider> = emptyList()
    private val initializing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val isInitialized: Boolean get() = engine != null
    val currentBackend: String? get() = activeBackendName

    init {
        detectCrashedBackend()
        try { ProcessLifecycleOwner.get().lifecycle.addObserver(this) } catch (_: Exception) {}
    }

    /** Set the tool registry — call before initialize(). */
    fun setTools(toolRegistry: Map<String, AndroidTool>) {
        toolProviders = createLocalToolProviders(toolRegistry)
        Log.i(TAG, "Registered ${toolProviders.size} tools for local inference")
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(modelPath: String) {
        if (engine != null && currentModelPath == modelPath) return
        if (!initializing.compareAndSet(false, true)) {
            while (initializing.get()) delay(100)
            if (engine != null && currentModelPath == modelPath) return
        }

        try {
            release()
            Log.i(TAG, "Initializing with model: $modelPath")

            withContext(Dispatchers.IO) {
                val blacklisted = getBlacklisted()
                val lastGood = prefs.getString(KEY_LAST_GOOD, null)

                // Build candidate list: last known good first, then priority order
                val candidates = buildList {
                    if (lastGood != null && lastGood !in blacklisted) add(lastGood)
                    for (b in BACKEND_PRIORITY) {
                        if (b !in blacklisted && b != lastGood) add(b)
                    }
                }
                Log.i(TAG, "Backend candidates: $candidates (blacklisted: $blacklisted)")

                var lastError: Exception? = null
                for (backendName in candidates) {
                    try {
                        Log.i(TAG, "Trying backend: $backendName")
                        markAttempting(backendName) // safety net for native crash

                        val (eng, conv) = createEngineAndConversation(modelPath, backendName)
                        engine = eng
                        conversation = conv
                        currentModelPath = modelPath
                        activeBackendName = backendName

                        markSuccess(backendName)
                        Log.i(TAG, "Engine ready with backend: $backendName")
                        return@withContext // success
                    } catch (e: Exception) {
                        Log.w(TAG, "Backend $backendName failed: ${e.message}")
                        clearAttempting()
                        lastError = e
                        // Continue to next backend
                    }
                }

                throw LocalInferenceException(
                    "All backends failed. Last error: ${lastError?.message}",
                    lastError,
                )
            }
        } catch (e: LocalInferenceException) {
            engine = null; conversation = null; currentModelPath = null; activeBackendName = null
            throw e
        } catch (e: Exception) {
            engine = null; conversation = null; currentModelPath = null; activeBackendName = null
            throw LocalInferenceException("Failed to load model: ${e.message}", e)
        } finally {
            initializing.set(false)
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun createEngineAndConversation(modelPath: String, backendName: String): Pair<Engine, Conversation> {
        val backend = createBackend(backendName)
        val isNpu = backendName == "NPU"

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = 8192,
            cacheDir = context.cacheDir.absolutePath,
        )

        val newEngine = Engine(engineConfig)
        newEngine.initialize()

        // Enable constrained decoding for reliable JSON tool output
        val hasTools = toolProviders.isNotEmpty()
        if (hasTools) {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
        }

        val conv = newEngine.createConversation(
            ConversationConfig(
                samplerConfig = if (isNpu) null else SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = 0.7,
                ),
                tools = if (hasTools) toolProviders else emptyList(),
            ),
        )

        if (hasTools) {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }

        Log.i(TAG, "Conversation created with ${toolProviders.size} tools, constrained decoding=${hasTools}")
        return newEngine to conv
    }

    /**
     * Generate response with streaming via callback.
     */
    suspend fun generateResponse(
        messages: List<ClaudeMessage>,
        systemPrompt: String?,
        onDelta: ((String) -> Unit)? = null,
    ): ClaudeResponse {
        val conv = conversation ?: throw LocalInferenceException("Engine not initialized")

        return withContext(Dispatchers.IO) {
            cancelReleaseTimer()

            val userText = formatHistory(messages).lastOrNull()?.second ?: "Hello"
            val prompt = buildPrompt(formatHistory(messages), systemPrompt)
            Log.i(TAG, "Inference start: prompt=${prompt.length} chars (~${prompt.length / 4} tokens), backend=$activeBackendName")
            val startTime = System.currentTimeMillis()

            val responseBuilder = StringBuilder()
            val done = CompletableDeferred<Unit>()
            var tokenCount = 0
            var firstTokenTime = 0L

            conv.sendMessageAsync(
                Contents.of(Content.Text(prompt)),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val text = message.toString()
                        tokenCount++
                        if (tokenCount == 1) {
                            firstTokenTime = System.currentTimeMillis()
                            val ttft = firstTokenTime - startTime
                            Log.i(TAG, "First token in ${ttft}ms (TTFT)")
                        }
                        responseBuilder.append(text)
                        onDelta?.invoke(text)
                    }

                    override fun onDone() {
                        val totalMs = System.currentTimeMillis() - startTime
                        val decodeMs = if (firstTokenTime > 0) System.currentTimeMillis() - firstTokenTime else totalMs
                        val tokPerSec = if (decodeMs > 0) tokenCount * 1000.0 / decodeMs else 0.0
                        Log.i(TAG, "Inference done: $tokenCount tokens in ${totalMs}ms (decode: %.1f tok/s)".format(tokPerSec))
                        done.complete(Unit)
                    }

                    override fun onError(throwable: Throwable) {
                        val elapsed = System.currentTimeMillis() - startTime
                        Log.e(TAG, "Inference error after ${elapsed}ms: ${throwable.message}")
                        if (throwable is CancellationException) {
                            done.complete(Unit)
                        } else {
                            done.completeExceptionally(throwable)
                        }
                    }
                },
            )

            done.await()
            val responseText = responseBuilder.toString().trim()

            ClaudeResponse(
                id = "local-${UUID.randomUUID()}",
                model = currentModelPath?.substringAfterLast("/")?.substringBefore(".") ?: "gemma-4",
                role = "assistant",
                content = listOf(ContentBlock.TextBlock(text = responseText)),
                stopReason = "end_turn",
                inputTokens = (userText.length / 4).coerceAtLeast(1),
                outputTokens = (responseText.length / 4).coerceAtLeast(1),
                thinkingText = null,
            )
        }
    }

    fun release() {
        try { conversation?.close() } catch (e: Exception) { Log.w(TAG, "Error closing conversation: ${e.message}") }
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "Error closing engine: ${e.message}") }
        engine = null; conversation = null; currentModelPath = null; activeBackendName = null
        Log.i(TAG, "Engine released")
    }

    override fun onStop(owner: LifecycleOwner) { scheduleRelease() }
    override fun onStart(owner: LifecycleOwner) { cancelReleaseTimer() }

    // ── Backend creation ──────────────────────────────────────────────────

    private fun createBackend(name: String): Backend = when (name) {
        "NPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        "GPU" -> Backend.GPU()
        else -> Backend.CPU()
    }

    // ── Crash-resilient backend detection ─────────────────────────────────

    private fun detectCrashedBackend() {
        val attempting = prefs.getString(KEY_ATTEMPTING, null) ?: return
        Log.w(TAG, "Previous crash with backend: $attempting — blacklisting")
        val set = getBlacklisted().toMutableSet().apply { add(attempting) }
        prefs.edit().putString(KEY_BLACKLISTED, set.joinToString(",")).remove(KEY_ATTEMPTING).apply()
    }

    private fun markAttempting(b: String) { prefs.edit().putString(KEY_ATTEMPTING, b).commit() }
    private fun markSuccess(b: String) { prefs.edit().remove(KEY_ATTEMPTING).putString(KEY_LAST_GOOD, b).apply() }
    private fun clearAttempting() { prefs.edit().remove(KEY_ATTEMPTING).apply() }
    private fun getBlacklisted(): Set<String> =
        prefs.getString(KEY_BLACKLISTED, null)?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    // ── Prompt formatting ─────────────────────────────────────────────────

    private fun buildPrompt(history: List<Pair<String, String>>, systemPrompt: String?): String {
        val sb = StringBuilder()
        if (!systemPrompt.isNullOrBlank()) { sb.appendLine(systemPrompt); sb.appendLine() }
        val recent = history.takeLast(10)
        if (recent.size > 1) {
            for ((role, text) in recent.dropLast(1))
                sb.appendLine("${role.replaceFirstChar { it.uppercase() }}: $text")
            sb.appendLine()
        }
        sb.append(recent.lastOrNull()?.second ?: "")
        return sb.toString()
    }

    private fun formatHistory(messages: List<ClaudeMessage>): List<Pair<String, String>> {
        return messages.mapNotNull { msg ->
            val text = when (val c = msg.content) {
                is ClaudeContent.Text -> c.text
                is ClaudeContent.ContentList -> c.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("\n") { it.text }
                else -> return@mapNotNull null
            }
            if (text.isBlank()) return@mapNotNull null
            msg.role to text
        }
    }

    // ── Background release ────────────────────────────────────────────────

    private var backgroundReleaseJob: Job? = null
    private fun scheduleRelease() {
        backgroundReleaseJob = scope.launch { delay(BACKGROUND_RELEASE_DELAY_MS); release() }
    }
    private fun cancelReleaseTimer() { backgroundReleaseJob?.cancel(); backgroundReleaseJob = null }
}

class LocalInferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
