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
import ai.affiora.mobileclaw.channels.ChannelManager
import ai.affiora.mobileclaw.channels.NotificationChannel
import ai.affiora.mobileclaw.channels.SmsChannel
import ai.affiora.mobileclaw.channels.TelegramChannel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentService : Service() {

    @Inject
    lateinit var agentRuntime: AgentRuntime

    @Inject
    lateinit var channelManager: ChannelManager

    @Inject
    lateinit var telegramChannel: TelegramChannel

    @Inject
    lateinit var smsChannel: SmsChannel

    @Inject
    lateinit var notificationChannel: NotificationChannel

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = AgentBinder()

    inner class AgentBinder : Binder() {
        fun getRuntime(): AgentRuntime = agentRuntime
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("AgentService", "onCreate — starting service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Register and start messaging channels
        channelManager.registerChannel(telegramChannel)
        channelManager.registerChannel(smsChannel)
        channelManager.registerChannel(notificationChannel)
        channelManager.startAll()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduledAction = intent?.getStringExtra(EXTRA_SCHEDULED_ACTION)
        if (!scheduledAction.isNullOrBlank()) {
            serviceScope.launch {
                agentRuntime.run(
                    userMessage = scheduledAction,
                    conversationHistory = emptyList(),
                    systemPrompt = "",
                ).collect { /* consume events */ }
            }
        }

        // Ensure channels are running (handles system restarts via START_STICKY)
        ensureChannelsRunning()

        return START_STICKY
    }

    private fun ensureChannelsRunning() {
        if (!channelManager.isWatchdogRunning()) {
            android.util.Log.i("AgentService", "Channels not running, restarting...")
            channelManager.registerChannel(telegramChannel)
            channelManager.registerChannel(smsChannel)
            channelManager.registerChannel(notificationChannel)
            channelManager.startAll()
        }
    }

    override fun onDestroy() {
        channelManager.stopAll()
        serviceScope.cancel()
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
