package ai.affiora.mobileclaw.ui

import android.net.Uri

/**
 * Simple singleton to pass shared intent data from MainActivity to ChatScreen.
 * Consumed once on read — prevents stale data on config changes.
 */
object SharedIntentData {
    var pendingText: String? = null
    var pendingImageUri: Uri? = null

    fun consume(): Pair<String?, Uri?> {
        val text = pendingText
        val uri = pendingImageUri
        pendingText = null
        pendingImageUri = null
        return text to uri
    }
}
