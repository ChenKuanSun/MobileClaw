package ai.affiora.mobileclaw.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.affiora.mobileclaw.MobileClawApplication
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentService : Service() {

    @Inject
    lateinit var agentRuntime: AgentRuntime

    private val binder = AgentBinder()

    inner class AgentBinder : Binder() {
        fun getRuntime(): AgentRuntime = agentRuntime
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduledAction = intent?.getStringExtra(EXTRA_SCHEDULED_ACTION)
        if (!scheduledAction.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                agentRuntime.run(
                    userMessage = scheduledAction,
                    conversationHistory = emptyList(),
                    systemPrompt = "",
                ).collect { /* consume events */ }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        return NotificationCompat.Builder(this, MobileClawApplication.CHANNEL_AGENT_SERVICE)
            .setContentTitle("MobileClaw")
            .setContentText("Agent is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_SCHEDULED_ACTION = "scheduled_action"
    }
}
