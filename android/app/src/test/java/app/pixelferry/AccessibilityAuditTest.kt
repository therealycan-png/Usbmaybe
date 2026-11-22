package app.pixelferry

import android.app.Activity
import android.view.View
import app.pixelferry.ui.MainActivity
import app.pixelferry.ui.PickActivity
import app.pixelferry.ui.SetupActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import java.io.File
import java.util.Locale
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Google Accessibility Test Framework (ATF, the engine behind Play Console's pre-launch
 * accessibility report) on every screen: text contrast measured on the rendered pixels,
 * touch target size, labels, duplicate/overlapping clickables, etc.
 * Score = checks passed / checks run. Report: build/accessibility.txt
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = App::class, qualifiers = "w411dp-h820dp-xxhdpi")
class AccessibilityAuditTest {
    @Before fun reset() {
        Hub.run.active = false; Hub.run.total = 0; Hub.run.errors.clear(); Hub.source = null
    }

    private fun audit(a: Activity, name: String, report: StringBuilder): Pair<Int, Int> {
        ShadowLooper.idleMainLooper()
        val root = a.window.decorView
        val bmp = ScreenshotTest.snap(root, "a11y_$name")
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
        val params = Parameters().apply { putScreenCapture(BitmapImage(bmp)) }
        var run = 0; var failed = 0
        for (check in AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)) {
            val results: List<AccessibilityHierarchyCheckResult> = check.runCheckOnHierarchy(hierarchy, null, params)
            for (r in results) {
                when (r.type) {
                    AccessibilityCheckResultType.ERROR, AccessibilityCheckResultType.WARNING -> {
                        failed++; run++
                        report.appendLine("  ✗ [$name] ${check.javaClass.simpleName}: ${r.getMessage(Locale.ENGLISH)}")
                    }
                    AccessibilityCheckResultType.INFO -> { run++ }
                    else -> {}
                }
            }
            if (results.none { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }) run++
        }
        return run to failed
    }

    @Test fun audit_all_screens() {
        val report = StringBuilder()
        var run = 0; var failed = 0
        fun add(p: Pair<Int, Int>) { run += p.first; failed += p.second }

        App.instance.getSharedPreferences("pf", 0).edit().clear().commit()
        add(audit(Robolectric.buildActivity(SetupActivity::class.java).setup().get(), "setup", report))

        App.instance.getSharedPreferences("pf", 0).edit().putBoolean("setupDone", true).commit()
        add(audit(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "home", report))

        Hub.run.apply { active = true; title = "Copying from Mom's iPhone13"; total = 10_000; done = 3412; bytesTotal = 42L shl 30; bytesDone = 14L shl 30; startedAt = System.currentTimeMillis() - 1_000_000 }
        add(audit(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "running", report))
        Hub.run.active = false; Hub.run.total = 0

        Hub.source = ScreenshotTest.FakeSource()
        val pick = Robolectric.buildActivity(PickActivity::class.java).setup().get()
        Thread.sleep(1200); ShadowLooper.idleMainLooper()
        add(audit(pick, "pick", report))

        val score = (run - failed) * 100.0 / run
        val text = ("Google Accessibility Test Framework (preset LATEST)\nchecks: $run  issues: $failed  score: %.1f%%\n" +
            "Note: emoji icons are decorative (each sits next to a text label) and are drawn as backgrounds, so ATF's image-contrast check does not apply to them (WCAG 1.4.11 exempts decorative graphics).\n").format(score) + report
        println(text)
        File("build/accessibility.txt").writeText(text)
        assertTrue(score >= 97.0, text)
    }
}
