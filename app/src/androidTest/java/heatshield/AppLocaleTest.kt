package heatshield

import android.app.LocaleManager
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy is Australian English only, but Android picks plural forms and number formats by the locale it reads
 * resources in. Indonesian has no singular plural form and a decimal comma, so it shows both ways the phone's
 * language could leak into the app.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33) // Per-app languages and `cmd locale` arrived in Android 13.
class AppLocaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val localeManager by lazy { requireNotNull(context.getSystemService(LocaleManager::class.java)) }
    // The app's own language list before this test, usually empty ("follow the phone"). It is given back afterwards.
    private var originalLocales: LocaleList = LocaleList.getEmptyLocaleList()

    @Before
    fun giveTheAppIndonesian() {
        originalLocales = localeManager.applicationLocales
        shell("cmd locale set-app-locales ${context.packageName} --locales id-ID")
    }

    @After
    fun giveTheAppItsOwnLanguagesBack() {
        val tags = originalLocales.toLanguageTags()
        // With no --locales the list is empty, and the app follows the phone again.
        val locales = if (tags.isEmpty()) "" else " --locales $tags"
        shell("cmd locale set-app-locales ${context.packageName}$locales")
        // Later test classes read the app's configuration directly, so wait until the change has arrived: the first
        // language the app had before, or anything but Indonesian when it followed the phone.
        val first = if (tags.isEmpty()) null else originalLocales[0].toLanguageTag()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!configurationRestored(first) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("The app's own languages were not given back", tags, localeManager.applicationLocales.toLanguageTags())
    }

    @Test
    fun mainActivityKeepsEnglishPluralsAndNumbersInAnyAppLanguage() {
        assertEquals("The app language was not applied", "id-ID", localeManager.applicationLocales.toLanguageTags())
        var shown = emptyList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val resources = activity.resources
                shown = listOf(
                    resources.getQuantityString(R.plurals.log_shift_count, 1, 1),
                    resources.getQuantityString(R.plurals.ribbon_degrees, 1, 1),
                    chipTemperature(resources.getString(R.string.preview_sensor_chip, "10:22", 28.3, 47.0))
                )
            }
        }
        // Compared on the test thread, so a failure is reported here rather than thrown on the main thread.
        assertEquals(listOf("1 shift", "1 degree", "28.3°"), shown)
    }

    /** The one-decimal temperature in Task 9's sensor chip, whatever words surround it. */
    private fun chipTemperature(chip: String): String = Regex("""\d+[.,]\d°""").find(chip)?.value ?: chip

    /** True once the app's configuration reads [first], or, when [first] is null, has left Indonesian. */
    private fun configurationRestored(first: String?): Boolean {
        val shown = context.resources.configuration.locales[0].toLanguageTag()
        return if (first == null) shown != "id-ID" else shown == first
    }

    /** Runs [command] as the shell user. Reading its output to the end waits for it to finish. */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
