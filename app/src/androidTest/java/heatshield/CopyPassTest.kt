package heatshield

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyPassTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.let { original ->
        val config = android.content.res.Configuration(original.resources.configuration)
        config.setLocale(Locale.forLanguageTag("en-AU"))
        original.createConfigurationContext(config)
    }

    @Test
    fun logAndSearchCountsUseEnglishSingularAndPluralResources() {
        val resources = context.resources
        assertEquals("0 shifts", resources.getQuantityString(R.plurals.log_shift_count, 0, 0))
        assertEquals("1 shift", resources.getQuantityString(R.plurals.log_shift_count, 1, 1))
        assertEquals("2 shifts", resources.getQuantityString(R.plurals.log_shift_count, 2, 2))
        assertEquals("1 result", resources.getQuantityString(R.plurals.log_result_count, 1, 1))
        assertEquals("3 results", resources.getQuantityString(R.plurals.log_result_count, 3, 3))
        assertEquals("1 shift logged", resources.getQuantityString(R.plurals.trends_shifts_logged, 1, 1))
        assertEquals("4 shifts logged", resources.getQuantityString(R.plurals.trends_shifts_logged, 4, 4))
    }

    // Today's "You said: …" line and the break sheet open with the same words as the answer the gardener tapped.
    @Test
    fun answerLinesLeadWithTheAnswer() {
        val noShade = context.getString(R.string.shift_answer_no_shade_line)
        assertTrue(noShade, noShade.startsWith(context.getString(R.string.break_check_answer_no_shade)))
        val cantStop = context.getString(R.string.shift_answer_cant_stop_line, "10 min")
        assertTrue(cantStop, cantStop.startsWith(context.getString(R.string.break_check_answer_cant_stop)))
    }

    // Spec §8 glossary and voice, checked against every string and plural the app ships (M1–M5 files alike).
    private val banned = listOf(
        Regex("\\bsessions?\\b", RegexOption.IGNORE_CASE), Regex("\\brecords\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(a|the|this|each|your|per|one|every) record\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcontext\\b", RegexOption.IGNORE_CASE), Regex("\\breplay\\b", RegexOption.IGNORE_CASE), Regex("\\bcatalogue\\b", RegexOption.IGNORE_CASE),
        Regex("Room database", RegexOption.IGNORE_CASE), Regex("cloud identity", RegexOption.IGNORE_CASE), Regex("\\bRH\\b"),
        Regex("\\bburden\\b", RegexOption.IGNORE_CASE), Regex("device mode", RegexOption.IGNORE_CASE), Regex("Needs review"), Regex("\\bReviewed\\b"),
        Regex("\\b(to_raise|no_shade|cant_stop|break_due|heatshield\\.action)\\b"), Regex("!")
    )

    @Test
    fun noBannedWordInAnyUserFacingString() {
        val resources = context.resources
        val offences = mutableListOf<String>()
        R.string::class.java.fields.forEach { field ->
            val text = resources.getString(field.getInt(null))
            banned.firstOrNull { it.containsMatchIn(text) }?.let { offences += "${field.name}: \"$text\" matches ${it.pattern}" }
        }
        R.plurals::class.java.fields.forEach { field ->
            listOf(1, 2).forEach { n ->
                // The unformatted form: some plurals take more than the count ("%1$d shifts · %2$s"), and the words are
                // what is checked here, not what fills them.
                val text = resources.getQuantityText(field.getInt(null), n).toString()
                banned.firstOrNull { it.containsMatchIn(text) }?.let { offences += "${field.name}[$n]: \"$text\" matches ${it.pattern}" }
            }
        }
        assertTrue(offences.joinToString("\n"), offences.isEmpty())
    }
}
