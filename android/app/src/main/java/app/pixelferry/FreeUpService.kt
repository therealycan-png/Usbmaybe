package app.pixelferry

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.pixelferry.core.FreeUpAction

/**
 * Taps Google Photos' own "Free up space" -> "Free up X GB".
 * Only active while armed by a waiting wave, only inside Google Photos, gives up after 90 s.
 * Google Photos decides what is removed (only items already backed up).
 */
class FreeUpService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var clicks = 0
    private var lastClick = 0L
    private var lastScan = 0L

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed || event?.packageName?.toString() != PhotosWatcher.PHOTOS) return
        val now = System.currentTimeMillis()
        if (now - lastClick < 1200 || now - lastScan < 700) return // keep the main thread free
        lastScan = now
        val root = rootInActiveWindow ?: return
        step(root)
    }

    private fun step(root: AccessibilityNodeInfo) {
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        val texts = all.mapNotNull { (it.text ?: it.contentDescription)?.toString()?.trim() }
        if (texts.any { t -> NOTHING.any { t.lowercase().contains(it) } }) { finish(true, "nothing left to free"); return }

        // 1) The final confirm button: "Free up 3.2 GB" / "Free up"
        all.firstOrNull { n -> label(n)?.let { CONFIRM.matches(it) } == true && clickableSelfOrParent(n) != null }
            ?.let {
                Hub.tracker.openFreeUpWindow() // Google's own confirm is being pressed now
                click(it); main.postDelayed({ finish(true, "freed") }, 6000); return
            }
        // 2) The menu entry: "Free up space" / "Free up device storage"
        all.firstOrNull { n -> label(n)?.lowercase()?.let { l -> MENU.any { l.contains(it) } } == true }
            ?.let { click(it); return }
        // 3) Open the account menu (profile picture, top right)
        all.firstOrNull { n -> n.contentDescription?.toString()?.lowercase()?.let { d -> ACCOUNT.any { d.contains(it) } } == true }
            ?.let { if (clicks < 6) click(it) }
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim()

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null || out.size > 800) return
        out += n
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        repeat(4) { if (c == null) return null; if (c!!.isClickable && c!!.isEnabled) return c; c = c!!.parent }
        return null
    }

    private fun click(n: AccessibilityNodeInfo) {
        val target = clickableSelfOrParent(n) ?: return
        if (!target.isVisibleToUser) return
        clicks++; lastClick = System.currentTimeMillis()
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun finish(ok: Boolean, why: String) {
        if (!armed) return
        armed = false
        main.removeCallbacksAndMessages(null)
        FreeUpController.onAutoResult(this, ok, why)
        // come back to PixelFerry
        startActivity(Intent(this, app.pixelferry.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun arm() {
        armed = true; clicks = 0; lastClick = 0
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ finish(false, "timed out") }, 90_000)
        main.postDelayed({ rootInActiveWindow?.let { step(it) } }, 2500)
    }

    companion object {
        @Volatile var instance: FreeUpService? = null
        @Volatile var armed = false
        private val CONFIRM = Regex("(?i)^free up([\\s\\u00A0]+[0-9.,]+[\\s\\u00A0]*(kb|mb|gb|tb))?$")
        private val MENU = listOf("free up space", "free up device storage", "free up storage")
        private val ACCOUNT = listOf("account", "profile")
        private val NOTHING = listOf("nothing to free", "no items to free", "already been removed")

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return flat.contains(ComponentName(ctx, FreeUpService::class.java).flattenToString())
        }
    }
}

/** Decides auto-tap vs "please tap" notification. */
object FreeUpController : FreeUpAction {
    @Volatile var lastResult = ""

    override fun requestFreeUp(auto: Boolean): Boolean {
        val ctx = App.instance
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val svc = FreeUpService.instance
        val canAuto = auto && svc != null && pm.isInteractive && !km.isKeyguardLocked
        if (!canAuto) {
            Notifs.askFreeUp(ctx)
            return false
        }
        Handler(Looper.getMainLooper()).post {
            svc!!.arm()
            ctx.packageManager.getLaunchIntentForPackage(PhotosWatcher.PHOTOS)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } ?: Notifs.askFreeUp(ctx)
        }
        return true
    }

    fun onAutoResult(ctx: Context, ok: Boolean, why: String) {
        lastResult = if (ok) "Free up space done ($why)" else "Couldn't tap Free up space ($why)"
        if (!ok) Notifs.askFreeUp(ctx)
    }
}
