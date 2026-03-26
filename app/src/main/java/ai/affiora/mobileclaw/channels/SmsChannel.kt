package ai.affiora.mobileclaw.channels

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsChannel(
    private val context: Context,
) : Channel {

    override val id = "sms"
    override val displayName = "SMS"
    override var isRunning = false
        private set

    // Injected after construction to break circular dependency
    lateinit var channelManager: ChannelManager

    private val pairedNumbers = mutableSetOf<String>()
    private var receiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "SmsChannel"
        private const val PREFS = "sms_channel"
        private const val KEY_PAIRED = "paired_numbers"
    }

    init {
        loadPaired()
    }

    override suspend fun start() {
        if (isRunning) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECEIVE_SMS permission not granted")
            return
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val sender = sms.originatingAddress ?: continue
                    val text = sms.messageBody ?: continue

                    CoroutineScope(Dispatchers.IO).launch {
                        channelManager.onMessageReceived(
                            IncomingMessage(
                                channelId = "sms",
                                chatId = sender,
                                senderId = sender,
                                senderName = sender,
                                text = text,
                                timestamp = sms.timestampMillis,
                            ),
                        )
                    }
                }
            }
        }

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        isRunning = true
    }

    override fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
        }
        receiver = null
        isRunning = false
    }

    override suspend fun sendMessage(chatId: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SEND_SMS permission not granted")
            return
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }

        try {
            val parts = smsManager.divideMessage(text)
            if (parts.size == 1) {
                smsManager.sendTextMessage(chatId, null, text, null, null)
            } else {
                smsManager.sendMultipartTextMessage(chatId, null, parts, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $chatId", e)
        }
    }

    override fun isAllowed(senderId: String) = senderId in pairedNumbers

    override fun pair(senderId: String, senderName: String) {
        pairedNumbers.add(senderId)
        savePaired()
    }

    override fun unpair(senderId: String) {
        pairedNumbers.remove(senderId)
        savePaired()
    }

    override fun getPairedSenders(): List<PairedSender> {
        return pairedNumbers.map {
            PairedSender(id = it, name = it, channelId = "sms")
        }
    }

    // ── Persistence ──

    private fun getPrefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPaired() {
        val set = getPrefs().getStringSet(KEY_PAIRED, emptySet()) ?: emptySet()
        pairedNumbers.clear()
        pairedNumbers.addAll(set)
    }

    private fun savePaired() {
        getPrefs().edit().putStringSet(KEY_PAIRED, pairedNumbers.toSet()).apply()
    }
}
