package app.pixelferry

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.pixelferry.core.BackupSignals

/**
 * Watches ONLY Google Photos' notifications to know when its backup is running
 * ("Backing up 1,234 items…"). Everything else is ignored and nothing is stored.
 */
class PhotosWatcher : NotificationListenerService() {
    override fun onListenerConnected() { refresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (sbn.packageName == PHOTOS) refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.packageName == PHOTOS) refresh() }
    override fun onListenerDisconnected() { connected = false }

    private fun refresh() {
        connected = true
        val active = try { activeNotifications?.filter { it.packageName == PHOTOS } ?: emptyList() } catch (_: Exception) { emptyList() }
        val busy = active.any { looksLikeBackup(it.notification) }
        if (busy || backingUp) lastActivity = System.currentTimeMillis()
        if (busy) lastText = active.firstNotNullOfOrNull { text(it.notification) } ?: "Backing up"
        backingUp = busy
    }

    private fun text(n: Notification): String? {
        val e = n.extras
        return listOfNotNull(e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT))
            .joinToString(" · ").ifBlank { null }
    }

    private fun looksLikeBackup(n: Notification): Boolean {
        val e = n.extras
        val t = (text(n) ?: "").lowercase()
        return KEYWORDS.any { it in t }
    }

    object Signals : BackupSignals {
        override fun backingUp(): Boolean? = if (connected) backingUp else null
        override fun lastActivityMs(): Long = lastActivity
    }

    companion object {
        const val PHOTOS = "com.google.android.apps.photos"
        private val KEYWORDS = listOf("backing up", "uploading", "backup in progress", "preparing backup", "items left")
        @Volatile var connected = false
        @Volatile var backingUp = false
        @Volatile var lastActivity = 0L
        @Volatile var lastText = ""

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, PhotosWatcher::class.java).flattenToString())
        }
    }
}
