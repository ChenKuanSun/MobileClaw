package ai.affiora.mobileclaw.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic WorkManager worker that ensures AgentService is running.
 * Acts as a backup in case the foreground service is killed by the system.
 * Scheduled every 15 minutes (WorkManager minimum interval).
 */
class ChannelHealthWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Health check — ensuring AgentService is running")
        return try {
            applicationContext.startForegroundService(
                Intent(applicationContext, AgentService::class.java),
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart AgentService", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ChannelHealthWorker"
        const val WORK_NAME = "channel_health"
    }
}
