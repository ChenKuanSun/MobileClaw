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
    val intervalHours: Long,
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
        val intervalHours: Long,
        val skillAction: String,
    )

    fun scheduleRecurring(name: String, intervalHours: Long, skillAction: String) {
        val workRequest = PeriodicWorkRequestBuilder<ScheduledAgentWorker>(
            intervalHours, TimeUnit.HOURS,
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
            intervalHours = intervalHours,
            skillAction = skillAction,
        )
    }

    fun cancelSchedule(name: String) {
        workManager.cancelUniqueWork(uniqueWorkName(name))
        scheduledItems.remove(name)
    }

    fun listSchedules(): List<ScheduleInfo> {
        return scheduledItems.map { (name, metadata) ->
            val nextRunTime = getNextRunTime(name)
            ScheduleInfo(
                name = name,
                intervalHours = metadata.intervalHours,
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
        private const val WORK_PREFIX = "androidclaw_schedule_"
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
