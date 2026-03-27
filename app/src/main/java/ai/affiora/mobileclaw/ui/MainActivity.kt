package ai.affiora.mobileclaw.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ai.affiora.mobileclaw.agent.AgentService
import ai.affiora.mobileclaw.connectors.ConnectorManager
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.ui.theme.MobileClawTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var connectorManager: ConnectorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthRedirect(intent)

        // Start AgentService (runs channels, scheduled tasks)
        try {
            android.util.Log.d("MainActivity", "Starting AgentService...")
            startForegroundService(Intent(this, AgentService::class.java))
            android.util.Log.d("MainActivity", "AgentService start requested")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start AgentService", e)
        }

        setContent {
            MobileClawTheme {
                MobileClawApp(userPreferences)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "mobileclaw" && data.host == "oauth" && data.path == "/callback") {
            Log.d("MainActivity", "OAuth callback received: $data")
            lifecycleScope.launch(Dispatchers.IO) {
                connectorManager.handleOAuthCallback(intent).fold(
                    onSuccess = { connectorId ->
                        Log.d("MainActivity", "OAuth connected: $connectorId")
                    },
                    onFailure = { error ->
                        Log.e("MainActivity", "OAuth failed", error)
                    },
                )
            }
        }
    }
}
