package ai.affiora.mobileclaw.channels

import android.content.Context
import android.util.Log
import ai.affiora.mobileclaw.tools.ClawNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Channel that receives messages from messaging app notifications
 * (WhatsApp, LINE, Messenger, native Telegram app) via ClawNotificationListener.
 *
 * Sending replies is limited -- would require AccessibilityService to type in-app.
 * For now, replies are logged but not delivered.
 */
class NotificationChannel(
    private val context: Context,
) : Channel {

    override val id = "notifications"
    override val displayName = "App Notifications"
    override var isRunning = false
        private set

    // Injected after construction to break circular dependency
    lateinit var channelManager: ChannelManager

    /** Package names to monitor for incoming messages. */
    private val monitoredApps = setOf(
        "com.whatsapp",
        "jp.naver.line.android",
        "org.telegram.messenger",
        "com.facebook.orca",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairedSenders = mutableSetOf<String>()

    companion object {
        private const val TAG = "NotificationChannel"
        private const val PREFS = "notification_channel"
        private const val KEY_PAIRED = "paired_senders"
    }

    init {
        loadPaired()
    }

    override suspend fun start() {
        if (isRunning) return

        ClawNotificationListener.onNotificationCallback = { pkg, title, text, time ->
            if (pkg in monitoredApps && text != null) {
                scope.launch {
                    channelManager.onMessageReceived(
                        IncomingMessage(
                            channelId = "notifications",
                            chatId = "$pkg:${title ?: "unknown"}",
                            senderId = title ?: pkg,
                            senderName = title ?: pkg,
                            text = text,
                            timestamp = time,
                        ),
                    )
                }
            }
        }
        isRunning = true
    }

    override fun stop() {
        scope.cancel()
        ClawNotificationListener.onNotificationCallback = null
        isRunning = false
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        // Full reply requires AccessibilityService to type in the app.
        // For now, just log. Full implementation would use UIAutomationTool.
        Log.i(TAG, "Would reply to $chatId: ${text.take(100)}")
    }

    override fun isAllowed(senderId: String) = senderId in pairedSenders

    override fun pair(senderId: String, senderName: String) {
        pairedSenders.add(senderId)
        savePaired()
    }

    override fun unpair(senderId: String) {
        pairedSenders.remove(senderId)
        savePaired()
    }

    override fun getPairedSenders(): List<PairedSender> {
        return pairedSenders.map {
            PairedSender(id = it, name = it, channelId = "notifications")
        }
    }

    // ── Persistence ──

    private fun getPrefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPaired() {
        val set = getPrefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()
        pairedSenders.clear()
        pairedSenders.addAll(set)
    }

    private fun savePaired() {
        getPrefs().edit().putStringSet(KEY_PAIRED, pairedSenders.toSet()).apply()
    }
}
