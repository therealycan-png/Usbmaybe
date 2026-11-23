package app.pixelferry.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.pixelferry.GooglePhotos

/**
 * First-run setup: one step per screen, one big button, moves on by itself
 * as soon as the step is done. Can be reopened from the home screen.
 */
class SetupActivity : Activity() {

    class Step(
        val symbol: String, val tint: Int, val title: String, val body: String,
        val action: String, val optional: Boolean = false,
        val done: (Context) -> Boolean, val go: (SetupActivity) -> Unit,
    )

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        window.statusBarColor = C.BG
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root = vbox(28).apply { gravity = Gravity.CENTER; setBackgroundColor(C.BG) }
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(C.BG); addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()

    private fun render() {
        root.removeAllViews()
        val steps = steps()
        val idx = steps.indexOfFirst { !it.done(this) && !skipped(it) }
        root.addView(dots(steps.size, if (idx < 0) steps.size else idx))
        if (idx < 0) { allSet(); return }
        val s = steps[idx]
        root.addView(spacer(28))
        root.addView(badge(s.symbol, s.tint, 112).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(spacer(24))
        root.addView(text("Step ${idx + 1} of ${steps.size}", 14f, C.PRIMARY, bold = true).apply { gravity = Gravity.CENTER })
        root.addView(text(s.title, 26f, C.INK, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10)) })
        root.addView(text(s.body, 17f, C.INK2).apply { gravity = Gravity.CENTER })
        root.addView(spacer(28))
        root.addView(btn(s.action, primary = true) { s.go(this) })
        if (s.title.startsWith("Google Photos")) root.addView(btn("Open Google Photos to check") {
            GooglePhotos.open(this)
        })
        if (s.optional) root.addView(link("Skip this (you'll tap once per wave instead)") {
            getSharedPreferences("pf", 0).edit().putBoolean("skip_${s.title}", true).apply(); render()
        })
        root.addView(link("Do this later") { finish() })
    }

    private fun allSet() {
        root.addView(spacer(40))
        root.addView(badge("🎉", C.TINT_GREEN, 112).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(spacer(24))
        root.addView(text("You're all set", 28f, C.INK, bold = true).apply { gravity = Gravity.CENTER })
        root.addView(text("Plug a phone, tablet or camera into the Pixel, or send from your computer.", 17f, C.INK2)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        root.addView(spacer(28))
        getSharedPreferences("pf", 0).edit().putBoolean("setupDone", true).apply()
        root.addView(btn("Let's go", primary = true) { finish() })
    }

    /** Open a settings screen; if this phone doesn't have it, fall back to the app's own settings page. */
    fun open(i: Intent, hint: String?) {
        try { startActivity(i) } catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        hint?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun skipped(s: Step) = s.optional && getSharedPreferences("pf", 0).getBoolean("skip_${s.title}", false)

    private fun spacer(h: Int) = android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun dots(n: Int, current: Int) = hbox().apply {
        gravity = Gravity.CENTER
        for (i in 0 until n) addView(android.view.View(context).apply {
            background = rounded(if (i <= current) C.PRIMARY else C.LINE, dpf(5f))
            layoutParams = LinearLayout.LayoutParams(if (i == current) dp(28) else dp(10), dp(10)).apply { setMargins(dp(4), 0, dp(4), 0) }
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        fun steps(): List<Step> = listOf(
            Step("📁", C.TINT_BLUE, "Save photos on this Pixel",
                "PixelFerry puts your photos in a PixelFerry folder so Google Photos backs them up.",
                "Allow",
                done = { it.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED },
                go = {
                    val p = it.getSharedPreferences("pf", 0)
                    if (p.getBoolean("askedStorage", false) && !it.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        // "Don't ask again" was chosen earlier: Android won't show the dialog, so open the app's settings
                        it.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${it.packageName}")),
                            "Tap Permissions › Storage › Allow")
                    } else {
                        p.edit().putBoolean("askedStorage", true).apply()
                        it.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
                    }
                }),
            Step("🔋", C.TINT_GREEN, "Keep going with the screen off",
                "Big transfers can take hours. This stops Android from pausing PixelFerry.",
                "Allow",
                done = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
                go = { it.open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")), null) }),
            Step("☁️", C.TINT_BLUE, "Google Photos on Original quality",
                "In Google Photos: profile picture › Photos settings › Backup › Backup quality must say \"Original quality\".\n\nIf it says Storage saver, Google keeps a smaller copy, and deleting the originals later would lose quality.",
                "I checked: it says Original quality",
                done = { it.getSharedPreferences("pf", 0).getBoolean("qualityOriginal", false) },
                go = { it.getSharedPreferences("pf", 0).edit().putBoolean("qualityOriginal", true).apply(); it.render() }),
        )

        fun remaining(ctx: Context): Int = steps().count { s ->
            !s.done(ctx) && !(s.optional && ctx.getSharedPreferences("pf", 0).getBoolean("skip_${s.title}", false))
        }
    }
}
