package heatshield

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real activity and navigates its public controls. Screenshots are genuine
 * emulator display captures. Assertions cover state transitions and shared data,
 * not backend behaviour that the A2 skeleton does not implement.
 */
@RunWith(AndroidJUnit4::class)
class PrototypeJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val screenshots: File
        get() = File(
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "screenshots"
        )

    @Test
    fun navigateCaptureAndVerifyPrototypeJourney() {
        assertTrue(screenshots.exists() || screenshots.mkdirs())
        File(screenshots, "capture-manifest.csv").writeText("filename,captured_at_epoch_ms\n")

        // Password visibility is an explicit user control; sign-up shows correction.
        compose.onNodeWithText("Welcome to HeatShield").assertIsDisplayed()
        capture("01_login.png")
        click("Forgot password?")
        compose.onNodeWithText("Password reset preview").assertIsDisplayed()
        compose.onNodeWithText("This is the preview of reset password.")
            .assertIsDisplayed()
        click("Dismiss", scroll = false)
        compose.onNodeWithText("Password reset preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        top("Welcome to HeatShield")
        capture("17_password.png")
        compose.onNodeWithContentDescription("Hide password").performScrollTo().performClick()
        click("New to HeatShield? Sign up")
        val passwordCorrection = "Too short. Use at least 12 characters, such as a memorable phrase."
        compose.onNodeWithText(passwordCorrection).assertDoesNotExist()
        top("Start with your next shift")
        capture("02_signup.png")
        replace("Password", "shade")
        compose.onNodeWithText(passwordCorrection).assertExists()
        compose.onNodeWithText("Create demo account").assertIsNotEnabled()
        replace("Password", "Shade before midday")
        compose.onNodeWithText(passwordCorrection).assertDoesNotExist()
        compose.onNodeWithText("Create demo account").assertIsEnabled()
        click("Create demo account")

        // Both availability branches retain the same navigation and avoid fake data.
        bottomNav("Today")
        compose.onNodeWithText("Time for a cooler pause").assertIsDisplayed()
        capture("03_today.png")
        click("Preview unavailable data")
        top("Make room for shade.")
        compose.onNodeWithText("CONTEXT UNAVAILABLE").assertIsDisplayed()
        compose.onNodeWithText("Forecast needs a refresh").assertIsDisplayed()
        capture("16_unavailable.png")
        click("Retry demo forecast")
        compose.onNodeWithText("CONTEXT UNAVAILABLE").assertDoesNotExist()

        // Actual date/time/dropdown overlays, followed by a persisted plan choice.
        click("Review")
        bottomNav("Today")
        compose.onNodeWithText("A better window for heavy work").assertIsDisplayed()
        capture("04_plan.png")
        clickMatching(hasText("Work date ·", substring = true))
        compose.onNodeWithText("Apply date").assertIsDisplayed()
        capture("13_datepicker.png")
        click("Apply date", scroll = false)
        clickMatching(hasText("Shift ·", substring = true))
        compose.onNodeWithText("Shift start").assertIsDisplayed()
        compose.onNodeWithText("Apply time").assertIsEnabled()
        capture("14_timepicker.png")
        click("Apply time", scroll = false)
        openChoice("Work type")
        compose.onNodeWithText("Pruning").assertExists()
        capture("15_dropdown.png")
        choose("Pruning")
        compose.onNodeWithText("Pruning").assertExists()
        openChoice("Work type")
        choose("Planting beds")
        click("Compare work windows")
        top("A better window for heavy work")
        compose.onNodeWithText("Hourly air temperature").assertIsDisplayed()
        reach(hasText("Higher forecast temperature and UV in this example.")).assertIsDisplayed()
        capture("12_compare.png")
        click("12:00–14:00")
        click("Save preferred window")
        reach(hasText("Heavy work · 12:00–14:00")).assertIsDisplayed()
        // Restore the preferred window used in the initial screenshot set.
        click("Review")
        click("Compare work windows")
        click("08:00–10:00")
        click("Save preferred window")
        reach(hasText("Heavy work · 08:00–10:00")).assertIsDisplayed()

        // Records, detail and edit share one entity rather than isolated mock screens.
        tab("Records")
        compose.onNodeWithText("6 sessions").assertExists()
        capture("05_records.png")
        click("Birrarung Marr · 12 Jan 2026")
        bottomNav("Records")
        compose.onNodeWithText("Session detail").assertIsDisplayed()
        capture("06_detail.png")
        click("Edit record")
        compose.onNodeWithText("Keep the record accurate").assertIsDisplayed()
        capture("07_edit.png")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        bottomNav("Records")
        click("Search")
        bottomNav("Records")
        compose.onNodeWithText("Find a past session").assertIsDisplayed()
        capture("08_search.png")

        // Text + status + task + date predicates are AND-combined, then reset.
        replace("Work type or site", "Birrarung")
        reach(hasText("4 results")).assertIsDisplayed()
        openChoice("Follow-up")
        choose("Needs review")
        reach(hasText("2 results")).assertIsDisplayed()
        openChoice("Work type")
        choose("Planting beds")
        reach(hasText("2 results")).assertIsDisplayed()
        click("Any date")
        click("Use date", scroll = false)
        reach(hasText("1 result")).assertIsDisplayed()
        click("Reset")
        reach(hasText("6 results")).assertIsDisplayed()
        replace("Work type or site", "No matching worksite")
        reach(hasText("No matching sessions")).assertIsDisplayed()
        click("Reset")
        reach(hasText("6 results")).assertIsDisplayed()
        openChoice("Sort results")
        choose("Longest outdoors")
        compose.onAllNodes(hasText("Outdoor work") and hasText("Logged breaks") and hasClickAction())
            .onFirst().assert(hasText("140 min"))
        click("Birrarung Marr · 9 Jan 2026")
        compose.onNodeWithText("140 min").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        bottomNav("Records")

        // Charts derive from records and keep work minutes separate from break minutes.
        tab("Trends")
        bottomNav("Trends")
        totals(610, 95)
        capture("09_trends.png")
        click("By week")
        reach(hasText("Logged minutes by week")).assertIsDisplayed()
        reach(hasText("Week of 5 Jan")).assertIsDisplayed()
        totals(610, 95)
        openChoice("Work type")
        choose("Mowing")
        totals(225, 40)
        openChoice("Work type")
        choose("All work")
        click("By day")
        totals(610, 95)

        // A worker who cannot pause gets follow-up, not a fabricated break/recovery.
        tab("Today")
        click("Check in now")
        bottomNav("Today")
        compose.onNodeWithText("What is possible right now?").assertIsDisplayed()
        capture("10_break.png")
        click("Cannot pause yet")
        compose.onNodeWithText("Discuss a work adjustment").assertExists()
        click("Record this response")
        top("Your response is ready")
        compose.onNodeWithText("Follow-up remains open").assertExists()
        capture("18_break_response.png")
        click("Return to Today")
        compose.onNodeWithText("A task change may help").assertIsDisplayed()
        tab("Trends")
        totals(610, 95)
        tab("Records")
        click("Birrarung Marr · 12 Jan 2026")
        reach(hasText("A work adjustment needs discussion. The follow-up remains open.")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        tab("Profile")
        bottomNav("Profile")
        compose.onNodeWithText("Your work context").assertIsDisplayed()
        capture("11_profile.png")
        val reminderSwitch = compose.onNodeWithContentDescription("Break check-in reminders")
        reminderSwitch.performScrollTo().assertIsOn().performClick().assertIsOff()
        reminderSwitch.performClick().assertIsOn()
        click("Save preferences (demo)")
        reach(hasText("Demo preferences saved")).assertIsDisplayed()
        reach(hasText("These selections stay within this prototype screen.")).assertIsDisplayed()
        capture("20_preferences.png")

        // CRUD runs after screenshots so submitted fixture captures stay coherent.
        tab("Records")
        click("Add record")
        compose.onNodeWithText("Record a work session").assertIsDisplayed()
        click("Add record")
        reach(hasText("Enter 1–960 minutes of outdoor work.")).assertIsDisplayed()
        capture("19_form_error.png")
        replace("Outdoor work · required", "40")
        replace("Logged breaks · required", "10")
        click("Add record")
        compose.onNodeWithText("7 sessions").assertExists()
        tab("Trends")
        totals(650, 105)
        tab("Records")
        clickMatching(hasText("Planting beds") and hasText("40 min"))
        click("Edit record")
        replace("Outdoor work · required", "45")
        replace("Logged breaks · required", "12")
        click("Save changes")
        compose.onNodeWithText("45 min").assertExists()
        compose.onNodeWithText("12 min").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        tab("Trends")
        totals(655, 107)
        tab("Records")
        clickMatching(hasText("Planting beds") and hasText("45 min"))
        click("Delete record")
        compose.onNodeWithText("Delete this record?").assertIsDisplayed()
        click("Keep record", scroll = false)
        compose.onNodeWithText("Delete this record?").assertDoesNotExist()
        click("Delete record")
        // The dialog confirm button follows the obscured screen's delete button.
        compose.onAllNodes(hasText("Delete record") and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("6 sessions").assertExists()
        tab("Trends")
        totals(610, 95)
        assertEquals(20, File(screenshots, "capture-manifest.csv").readLines().size - 1)
    }

    private fun selectable(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun tab(label: String) {
        compose.onNode(selectable(label)).performClick()
        compose.waitForIdle()
        bottomNav(label)
    }

    private fun bottomNav(selected: String) {
        listOf("Today", "Records", "Trends", "Profile").forEach {
            compose.onNode(selectable(it)).assertIsDisplayed()
        }
        compose.onNode(selectable(selected)).assertIsSelected()
    }

    private fun reach(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitForIdle()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            // LazyColumn has not composed the result item yet.
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun top(text: String) {
        reach(hasText(text))
        compose.waitForIdle()
    }

    private fun click(text: String, scroll: Boolean = true) = clickMatching(hasText(text), scroll)

    private fun clickMatching(matcher: SemanticsMatcher, scroll: Boolean = true) {
        val target = matcher and hasClickAction()
        if (scroll) reach(target).performClick() else compose.onNode(target).performClick()
        compose.waitForIdle()
    }

    private fun openChoice(label: String) {
        reach(hasText(label)).performClick()
        compose.waitForIdle()
    }

    private fun choose(option: String) {
        // Popup options occur after any equally labelled fields/cards underneath.
        compose.onAllNodes(hasText(option) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    private fun replace(label: String, value: String) {
        reach(hasText(label) and hasSetTextAction()).performTextReplacement(value)
        compose.runOnUiThread {
            val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun totals(outdoor: Int, breaks: Int) {
        top("Look back. Plan ahead.")
        compose.onNodeWithText(outdoor.toString()).assertIsDisplayed()
        compose.onNodeWithText(breaks.toString()).assertIsDisplayed()
    }

    private fun capture(filename: String) {
        compose.waitForIdle()
        Thread.sleep(200) // Allow platform popup/IME display animation to settle.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(screenshots, filename).outputStream().use {
            assertTrue("PNG capture failed: $filename", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        File(screenshots, "capture-manifest.csv").appendText("$filename,${System.currentTimeMillis()}\n")
    }
}
