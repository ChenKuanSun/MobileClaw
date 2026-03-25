package ai.affiora.mobileclaw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MobileClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
