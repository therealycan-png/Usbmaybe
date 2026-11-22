package app.pixelferry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import app.pixelferry.core.SourceEntry
import app.pixelferry.core.WaveCoordinator
import app.pixelferry.sources.MediaSource
import app.pixelferry.ui.MainActivity
import app.pixelferry.ui.PickActivity
import app.pixelferry.ui.SetupActivity
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Renders every screen to build/screens (PNG files) so the design can be checked by eye and by WcagTest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = App::class, qualifiers = "w411dp-h820dp-xxhdpi")
class ScreenshotTest {
    companion object {
        fun snap(v: View, name: String, fullHeight: Boolean = true): Bitmap {
            val root = v.rootView
            val w = root.width
            var h = root.height
            if (fullHeight) {
                // measure the whole scrollable content so long screens are captured in full
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                h = maxOf(h, root.measuredHeight)
                root.layout(0, 0, w, h)
            }
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(b))
            val out = File("build/screens").apply { mkdirs() }
            File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return b
        }
    }

    class FakeSource : MediaSource {
        override val deviceKey = "usb:1452:1:FAKE"
        override val suggestedLabel = "iPhone 13"
        override val kind = "usb"
        override fun isConnected() = true
        override fun list(progress: (Int) -> Unit) = (0 until 2480).map {
            val folder = listOf("DCIM/100APPLE", "DCIM/101APPLE", "DCIM/102APPLE")[it % 3]
            val video = it % 9 == 0
            SourceEntry("$folder/IMG_$it|${if (video) 60_000_000 else 3_000_000}|$it", if (video) "IMG_$it.MOV" else "IMG_$it.HEIC",
                if (video) 60_000_000L else 3_000_000L, 1_600_000_000_000L + it * 3_600_000L, folder, "$it")
        }
        override fun copyTo(entry: SourceEntry, dest: File) {}
        override fun delete(entry: SourceEntry) = false
        override fun thumbnail(entry: SourceEntry) = null
        override fun close() {}
    }

    @Before fun reset() {
        Hub.run.active = false; Hub.run.total = 0; Hub.run.errors.clear(); Hub.source = null; Hub.run.phase = Phase.IDLE
        App.instance.getSharedPreferences("pf", 0).edit().putBoolean("setupDone", true).commit()
    }

    @Test fun home_idle() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        snap(a.window.decorView, "1_home")
    }

    @Test fun home_running() {
        val r = Hub.run
        r.active = true; r.title = "Copying from Mom's iPhone13"; r.detail = "IMG_4821.HEIC"
        r.total = 10_000; r.done = 3_412; r.bytesTotal = 42L shl 30; r.bytesDone = (42L shl 30) * 34 / 100
        r.duplicates = 37; r.startedAt = System.currentTimeMillis() - 1_000_000
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        snap(a.window.decorView, "2_home_running")
        a.finish()
    }

    @Test fun home_waiting_for_google() {
        val r = Hub.run
        r.active = true; r.deviceLabel = "Mom's iPhone13"; r.title = "Pixel is full: waiting for Google"
        r.total = 10_000; r.done = 5_120; r.bytesTotal = 42L shl 30; r.bytesDone = 21L shl 30; r.startedAt = System.currentTimeMillis() - 3_000_000
        Hub.wave.beginWait(Long.MAX_VALUE / 8) // sets the waiting phase + text
        r.phase = Phase.WAITING_GOOGLE
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        snap(a.window.decorView, "4_home_waiting")
    }

    @Test fun home_done() {
        val r = Hub.run
        r.active = false; r.phase = Phase.DONE; r.deviceLabel = "Mom's iPhone13"; r.title = "All done 🎉"
        r.detail = "9,871 new, 129 duplicates skipped, 0 already done, 0 failed.\n9,871 files are backed up by Google and can be deleted from Mom's iPhone13."
        r.total = 10_000; r.done = 10_000; r.bytesTotal = 42L shl 30; r.bytesDone = 42L shl 30; r.duplicates = 129; r.startedAt = System.currentTimeMillis() - 9_000_000
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        snap(a.window.decorView, "5_home_done")
    }

    @Test fun setup_wizard() {
        App.instance.getSharedPreferences("pf", 0).edit().clear().commit()
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        snap(a.window.decorView, "0_setup_step1")
    }

    @Test fun pick() {
        Hub.source = FakeSource()
        val a = Robolectric.buildActivity(PickActivity::class.java).setup().get()
        // listing runs on a background thread
        repeat(100) { if (a.window.decorView.findViewWithTag<View>("heroTitle")?.let { (it as android.widget.TextView).text.contains("iPhone") } == true) Thread.sleep(30); ShadowLooper.idleMainLooper() }
        Thread.sleep(800); ShadowLooper.idleMainLooper()
        snap(a.window.decorView, "3_pick")
    }
}
