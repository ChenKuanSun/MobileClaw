package ai.affiora.mobileclaw.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * NotificationListenerService that caches incoming notifications in-memory.
 * Thread-safe via ConcurrentLinkedDeque, capped at MAX_CACHE_SIZE entries.
 */
class ClawNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val cached = CachedNotification(
            packageName = sbn.packageName,
            title = title,
            text = text,
            postTime = sbn.postTime
        )

        cache.addFirst(cached)

        // Trim if over max size
        while (cache.size > MAX_CACHE_SIZE) {
            cache.removeLast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op: we keep removed notifications in cache for query purposes.
    }

    companion object : NotificationCache {
        private const val MAX_CACHE_SIZE = 200
        private val cache = ConcurrentLinkedDeque<CachedNotification>()

        override fun getRecent(since: Long?, packageName: String?, limit: Int): List<CachedNotification> {
            return cache.asSequence()
                .let { seq ->
                    if (since != null) seq.filter { it.postTime > since } else seq
                }
                .let { seq ->
                    if (packageName != null) seq.filter { it.packageName == packageName } else seq
                }
                .take(limit)
                .toList()
        }

        override fun add(notification: CachedNotification) {
            cache.addFirst(notification)
            while (cache.size > MAX_CACHE_SIZE) {
                cache.removeLast()
            }
        }

        /**
         * Clear all cached notifications. Useful for testing.
         */
        fun clearCache() {
            cache.clear()
        }
    }
}
