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
    private const val ID_STUCK = 3 // separate, so "tap Free up space" reminders never hide it

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
            .setOnlyAlertOnce(true) // updating the same alert never buzzes again
            .setAutoCancel(true).setContentIntent(intent).build())
    }

    /** Manual fallback: one tap opens Google Photos; the free-up window is already open. */
    private fun cleanAlert() = nm(App.instance).cancel(ID_ALERT)

    override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {
        if (Hub.run.phase != Phase.WAITING_GOOGLE) Hub.run.waitStartedAt = System.currentTimeMillis()
        Hub.run.phase = Phase.WAITING_GOOGLE
        Hub.run.title = "Pixel is full: free up space"
        Hub.run.detail = "Paused because the Pixel is full. Nothing is lost. What to do is shown below."
        alert(App.instance, "Pixel is full: transfer paused", "Open PixelFerry: it shows what's taking the space and what to do.")
    }

    override fun stuck(reason: String) {
        val ctx = App.instance
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STUCK, Notification.Builder(ctx, App.CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("One setting in Google Photos")
            .setStyle(Notification.BigTextStyle().bigText("Google Photos isn't backing up the PixelFerry folder yet. Open Google Photos › profile picture › Photos settings › Backup › Back up device folders, and turn on every PixelFerry folder."))
            .setContentText("Turn on backup for the PixelFerry folder")
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(openApp(ctx)).build())
    }


    override fun resumed() { cleanAlert(); Hub.run.phase = Phase.COPYING; Hub.run.detail = "Space is back. Continuing." }
}
