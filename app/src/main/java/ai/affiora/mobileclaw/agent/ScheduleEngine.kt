package ai.affiora.mobileclaw.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleInfo(
    val name: String,
    val intervalMinutes: Long,
    val skillAction: String,
    val nextRunTime: Long,
)

@Singleton
class ScheduleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    // Track scheduled items so we can report interval/action without querying WorkManager tags
    private val scheduledItems = mutableMapOf<String, ScheduleMetadata>()

    private data class ScheduleMetadata(
        val intervalMinutes: Long,
        val skillAction: String,
    )

    init {
        loadFromPrefs()
    }

    private fun saveToPrefs() {
        val json = scheduledItems.entries.joinToString(";") { (name, meta) ->
            "${name}|${meta.intervalMinutes}|${meta.skillAction}"
        }
        context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit().putString("items", json).apply()
    }

    private fun loadFromPrefs() {
        val raw = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .getString("items", "") ?: return
        raw.split(";").filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size == 3) {
                scheduledItems[parts[0]] = ScheduleMetadata(
                    intervalMinutes = parts[1].toLongOrNull() ?: 60L,
                    skillAction = parts[2],
                )
            }
        }
    }

    fun scheduleRecurring(name: String, intervalMinutes: Long, skillAction: String) {
        val safeInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val workRequest = PeriodicWorkRequestBuilder<ScheduledAgentWorker>(
            safeInterval, TimeUnit.MINUTES,
        ).setInputData(
            workDataOf(
                KEY_SCHEDULE_NAME to name,
                KEY_SKILL_ACTION to skillAction,
            )
        ).addTag(tagForName(name))
            .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName(name),
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )

        scheduledItems[name] = ScheduleMetadata(
            intervalMinutes = safeInterval,
            skillAction = skillAction,
        )
        saveToPrefs()
    }

    fun cancelSchedule(name: String) {
        workManager.cancelUniqueWork(uniqueWorkName(name))
        scheduledItems.remove(name)
        saveToPrefs()
    }

    fun listSchedules(): List<ScheduleInfo> {
        return scheduledItems.map { (name, metadata) ->
            val nextRunTime = getNextRunTime(name)
            ScheduleInfo(
                name = name,
                intervalMinutes = metadata.intervalMinutes,
                skillAction = metadata.skillAction,
                nextRunTime = nextRunTime,
            )
        }
    }

    private fun getNextRunTime(name: String): Long {
        return try {
            val workInfos = workManager.getWorkInfosForUniqueWork(uniqueWorkName(name)).get()
            val info = workInfos.firstOrNull()
            if (info != null && info.state == WorkInfo.State.ENQUEUED) {
                info.nextScheduleTimeMillis
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun uniqueWorkName(name: String): String = "$WORK_PREFIX$name"

    private fun tagForName(name: String): String = "$TAG_PREFIX$name"

    companion object {
        const val MIN_INTERVAL_MINUTES = 15L
        private const val WORK_PREFIX = "mobileclaw_schedule_"
        private const val TAG_PREFIX = "schedule_"
        const val KEY_SCHEDULE_NAME = "schedule_name"
        const val KEY_SKILL_ACTION = "skill_action"
    }
}

class ScheduledAgentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val skillAction = inputData.getString(ScheduleEngine.KEY_SKILL_ACTION)
            ?: return Result.failure()

        // Start AgentService and send the scheduled action through it.
        // The service will pick up the action and run it through AgentRuntime.
        val serviceIntent = android.content.Intent(applicationContext, AgentService::class.java).apply {
            putExtra(EXTRA_SCHEDULED_ACTION, skillAction)
        }
        applicationContext.startForegroundService(serviceIntent)

        return Result.success()
    }

    companion object {
        const val EXTRA_SCHEDULED_ACTION = "scheduled_action"
    }
}
