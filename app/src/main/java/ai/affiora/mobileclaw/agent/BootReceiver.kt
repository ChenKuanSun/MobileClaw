package ai.affiora.mobileclaw.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Check onboarding flag file (avoids creating duplicate DataStore instance)
        val flagFile = context.filesDir.resolve(".onboarding_completed")
        if (!flagFile.exists()) return

        val serviceIntent = Intent(context, AgentService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
