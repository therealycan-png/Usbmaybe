package app.pixelferry

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pixelferry.core.WaveNotifier
import app.pixelferry.ui.MainActivity

object Notifs : WaveNotifier {
    const val ID_RUN = 1
    private const val ID_ALERT = 2

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    fun progress(ctx: Context, title: String, text: String, done: Int, total: Int): Notification =
        Notification.Builder(ctx, App.CH_RUN)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), done.coerceAtMost(total.coerceAtLeast(0)), total <= 0)
            .setContentIntent(openApp(ctx)).build()

    fun alert(ctx: Context, title: String, text: String, intent: PendingIntent = openApp(ctx)) {
        nm(ctx).notify(ID_ALERT, Notification.Builder(ctx, App.CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(intent).build())
    }

    /** Manual fallback: one tap opens Google Photos; the free-up window is already open. */
    /** Manual fallback. Tapping it goes through PixelFerry first, which opens the short free-up window. */
    fun askFreeUp(ctx: Context) {
        val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_FREE_UP_NOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        alert(ctx, "Tap: Free up space in Google Photos",
            "Google Photos looks done backing up. Tap here, then in Google Photos tap your profile picture > " +
                "Free up space, and confirm. PixelFerry continues by itself after that.", pi)
    }

    private fun cleanAlert() = nm(App.instance).cancel(ID_ALERT)

    override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {
        if (Hub.run.phase != Phase.WAITING_GOOGLE) Hub.run.waitStartedAt = System.currentTimeMillis()
        Hub.run.phase = Phase.WAITING_GOOGLE
        Hub.run.title = "Pixel is full: waiting for Google"
        Hub.run.detail = "Google Photos is backing up this batch (usually 10–30 min), then copying continues by itself."
    }

    override fun stuck(reason: String) {
        alert(App.instance, "One setting in Google Photos", "Google Photos isn't backing up the PixelFerry folder yet. Open Google Photos › profile picture › Photos settings › Backup › Back up device folders, and turn on every PixelFerry folder.")
    }

    override fun resumed() { cleanAlert(); Hub.run.phase = Phase.COPYING; Hub.run.detail = "Space is back. Continuing." }
}
