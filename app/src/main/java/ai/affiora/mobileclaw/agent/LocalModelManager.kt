package ai.affiora.mobileclaw.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localModelDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_models")

/** Metadata for a downloadable on-device model. */
data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val huggingFaceRepo: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val requiredRamMb: Long,
    val requiredStorageMb: Long,
)

/** Current state of a local model. */
sealed class ModelState {
    /** Device doesn't meet hardware requirements (API level, RAM). */
    data class NotAvailable(val reason: String) : ModelState()
    /** Ready to download. */
    data object NotDownloaded : ModelState()
    /** Currently downloading. */
    data class Downloading(val progress: Float) : ModelState()
    /** Downloaded and ready to use. */
    data class Downloaded(val path: String, val sizeBytes: Long) : ModelState()
    /** Download or load failed. */
    data class Error(val message: String) : ModelState()
}

/** Device hardware capabilities relevant to on-device inference. */
data class DeviceCapability(
    val apiLevel: Int,
    val apiLevelOk: Boolean,
    val totalRamMb: Long,
    val availableStorageMb: Long,
)

@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val MIN_API_LEVEL = 31 // Android 12

        val MODELS = listOf(
            LocalModelInfo(
                id = "gemma-4-e2b",
                displayName = "Gemma 4 E2B",
                huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
                fileName = "gemma-4-E2B-it.litertlm",
                fileSizeBytes = 2_580_000_000L, // ~2.58 GB
                requiredRamMb = 6_000,
                requiredStorageMb = 3_600,
            ),
            LocalModelInfo(
                id = "gemma-4-e4b",
                displayName = "Gemma 4 E4B",
                huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
                fileName = "gemma-4-E4B-it.litertlm",
                fileSizeBytes = 3_650_000_000L,
                requiredRamMb = 8_000,
                requiredStorageMb = 4_700,
            ),
        )

        fun getModelInfo(modelId: String): LocalModelInfo? =
            MODELS.firstOrNull { it.id == modelId }
    }

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true) // HuggingFace redirects to CDN
            .build()
    }

    // Per-model mutable state
    private val _modelStates = mutableMapOf<String, MutableStateFlow<ModelState>>()
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        // Initialize states based on what's already downloaded
        for (model in MODELS) {
            _modelStates[model.id] = MutableStateFlow(computeInitialState(model))
        }
    }

    /** Observable state for a specific model. */
    fun getModelState(modelId: String): StateFlow<ModelState> =
        _modelStates.getOrPut(modelId) {
            val info = getModelInfo(modelId)
            MutableStateFlow(if (info != null) computeInitialState(info) else ModelState.NotAvailable("Unknown model"))
        }.asStateFlow()

    /** All model states as a map. */
    fun getAllModelStates(): Map<String, StateFlow<ModelState>> =
        _modelStates.mapValues { it.value.asStateFlow() }

    /** Check device hardware capabilities. */
    fun getDeviceCapability(): DeviceCapability {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val availableStorageMb = stat.availableBytes / (1024 * 1024)

        return DeviceCapability(
            apiLevel = Build.VERSION.SDK_INT,
            apiLevelOk = Build.VERSION.SDK_INT >= MIN_API_LEVEL,
            totalRamMb = totalRamMb,
            availableStorageMb = availableStorageMb,
        )
    }

    /** Whether the device can run a specific model. */
    fun canRunModel(modelId: String): Boolean {
        val info = getModelInfo(modelId) ?: return false
        val capability = getDeviceCapability()
        return capability.apiLevelOk
            && capability.totalRamMb >= info.requiredRamMb
            && capability.availableStorageMb >= info.requiredStorageMb
    }

    /** Why the device can't run a model — returns null if it can. */
    fun getIncompatibilityReason(modelId: String): String? {
        val info = getModelInfo(modelId) ?: return "Unknown model"
        val cap = getDeviceCapability()
        return when {
            !cap.apiLevelOk -> "Requires Android 12+ (API 31)"
            cap.totalRamMb < info.requiredRamMb -> "Requires ${info.requiredRamMb / 1000} GB RAM (device has ${cap.totalRamMb / 1000} GB)"
            cap.availableStorageMb < info.requiredStorageMb -> "Requires ${info.requiredStorageMb / 1000} GB storage (${cap.availableStorageMb / 1000} GB available)"
            else -> null
        }
    }

    /** Get the local file path for a downloaded model, or null if not downloaded. */
    fun getModelPath(modelId: String): String? {
        val info = getModelInfo(modelId) ?: return null
        val file = File(modelsDir, info.fileName)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Whether any local model is downloaded and ready. */
    fun hasAnyDownloadedModel(): Boolean =
        MODELS.any { getModelPath(it.id) != null }

    /** Download a model from HuggingFace. Returns a flow of progress (0.0 to 1.0). */
    fun downloadModel(modelId: String): Flow<Float> = flow {
        val info = getModelInfo(modelId) ?: throw IllegalArgumentException("Unknown model: $modelId")

        val reason = getIncompatibilityReason(modelId)
        if (reason != null) throw IllegalStateException(reason)

        val stateFlow = _modelStates[modelId] ?: return@flow
        stateFlow.value = ModelState.Downloading(0f)
        emit(0f)

        val url = "https://huggingface.co/${info.huggingFaceRepo}/resolve/main/${info.fileName}"
        val targetFile = File(modelsDir, info.fileName)
        val tempFile = File(modelsDir, "${info.fileName}.tmp")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MobileClaw/1.0")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val contentLength = body.contentLength().let { if (it > 0) it else info.fileSizeBytes }

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L

                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = (totalBytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                        stateFlow.value = ModelState.Downloading(progress)
                        emit(progress)
                    }
                }
            }

            // Rename temp → final
            if (tempFile.exists()) {
                targetFile.delete()
                tempFile.renameTo(targetFile)
            }

            stateFlow.value = ModelState.Downloaded(targetFile.absolutePath, targetFile.length())
            persistDownloadState(modelId, targetFile.absolutePath)
            emit(1f)
        } catch (e: CancellationException) {
            tempFile.delete()
            stateFlow.value = ModelState.NotDownloaded
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            val msg = e.message ?: "Download failed"
            stateFlow.value = ModelState.Error(msg)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /** Cancel an in-progress download. */
    fun cancelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        val info = getModelInfo(modelId) ?: return
        File(modelsDir, "${info.fileName}.tmp").delete()
        _modelStates[modelId]?.value = ModelState.NotDownloaded
    }

    /** Delete a downloaded model and free storage. */
    suspend fun deleteModel(modelId: String) {
        withContext(Dispatchers.IO) {
            val info = getModelInfo(modelId) ?: return@withContext
            File(modelsDir, info.fileName).delete()
            File(modelsDir, "${info.fileName}.tmp").delete()
            _modelStates[modelId]?.value = ModelState.NotDownloaded
            clearDownloadState(modelId)
        }
    }

    /** Track a download job for cancellation. */
    fun trackDownloadJob(modelId: String, job: Job) {
        downloadJobs[modelId] = job
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun computeInitialState(info: LocalModelInfo): ModelState {
        val reason = getIncompatibilityReason(info.id)
        if (reason != null) return ModelState.NotAvailable(reason)

        val file = File(modelsDir, info.fileName)
        return if (file.exists() && file.length() > 0) {
            ModelState.Downloaded(file.absolutePath, file.length())
        } else {
            ModelState.NotDownloaded
        }
    }

    private suspend fun persistDownloadState(modelId: String, path: String) {
        context.localModelDataStore.edit { prefs ->
            prefs[stringPreferencesKey("model_path_$modelId")] = path
        }
    }

    private suspend fun clearDownloadState(modelId: String) {
        context.localModelDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("model_path_$modelId"))
        }
    }
}
