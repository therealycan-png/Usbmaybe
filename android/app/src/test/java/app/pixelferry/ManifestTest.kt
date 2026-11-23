package app.pixelferry

import android.content.ComponentName
import android.content.pm.PackageManager
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every Activity/Service class in the app must be declared in the manifest (otherwise: crash on open). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = App::class)
class ManifestTest {
    @Test fun every_screen_and_service_is_declared() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val src = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/src/main/java") }.first { it.isDirectory }
        val classes = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            val pkg = Regex("^package (\\S+)", RegexOption.MULTILINE).find(f.readText())!!.groupValues[1]
            Regex("^class (\\w+)\\s*(?:\\(|:)[^\\n]*\\b(Activity|Service|AccessibilityService|NotificationListenerService)\\(\\)", RegexOption.MULTILINE)
                .findAll(f.readText()).map { "$pkg.${it.groupValues[1]}" to it.groupValues[2] }
        }.toList()
        assertTrue(classes.size >= 4, "found $classes")
        for ((cls, kind) in classes) {
            val cn = ComponentName("app.pixelferry", cls)
            try {
                if (kind == "Activity") pm.getActivityInfo(cn, 0) else pm.getServiceInfo(cn, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                fail("$cls is not declared in AndroidManifest.xml: the app would crash when opening it")
            }
        }
    }
}
