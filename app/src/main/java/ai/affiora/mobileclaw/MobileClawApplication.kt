package ai.affiora.mobileclaw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ai.affiora.mobileclaw.agent.ChannelHealthWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MobileClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleHealthCheck()
    }

    private fun scheduleHealthCheck() {
        val healthWork = PeriodicWorkRequestBuilder<ChannelHealthWorker>(
            15, TimeUnit.MINUTES, // WorkManager minimum interval
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ChannelHealthWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            healthWork,
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val agentChannel = NotificationChannel(
            CHANNEL_AGENT_SERVICE,
            "Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MobileClaw agent background service"
        }

        val alertChannel = NotificationChannel(
            CHANNEL_AGENT_ALERTS,
            "Agent Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important agent notifications and results"
        }

        manager.createNotificationChannels(listOf(agentChannel, alertChannel))
    }

    companion object {
        const val CHANNEL_AGENT_SERVICE = "agent_service"
        const val CHANNEL_AGENT_ALERTS = "agent_alerts"
    }
}
