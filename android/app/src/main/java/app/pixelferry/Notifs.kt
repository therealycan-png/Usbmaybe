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
    fun askFreeUp(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(PhotosWatcher.PHOTOS)
        val pi = if (launch != null) PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_IMMUTABLE) else openApp(ctx)
        alert(ctx, "Tap: Free up space in Google Photos",
            "Pixel is full and Google Photos looks done backing up. Open Google Photos > your profile picture > " +
                "Free up space, and confirm. PixelFerry continues by itself after that.", pi)
    }

    private fun cleanAlert() = nm(App.instance).cancel(ID_ALERT)

    override fun waiting(neededBytes: Long, freeBytes: Long, attempt: Int) {
        Hub.run.title = "Waiting for Google Photos backup"
        Hub.run.detail = if (attempt == 0) "Pixel is full. Waiting until Google Photos finishes uploading this wave…"
        else "Ran Free up space (attempt $attempt). Waiting for room… ${FreeUpController.lastResult}"
    }

    override fun stuck(reason: String) { alert(App.instance, "PixelFerry needs you", reason) }

    override fun resumed() { cleanAlert(); Hub.run.title = "Transferring"; Hub.run.detail = "Room is back. Continuing." }
}
