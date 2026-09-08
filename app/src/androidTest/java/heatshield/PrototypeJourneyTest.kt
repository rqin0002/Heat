package heatshield

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import heatshield.ui.PrototypeViewModel

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
        compose.onNodeWithText("Log in").assertIsDisplayed()
        capture("01_login.png")
        click("Forgot password?")
        compose.onNodeWithText("Password reset preview").assertIsDisplayed()
        compose.onNodeWithText("Password reset is not connected in this demo. No email will be sent.")
            .assertIsDisplayed()
        click("Dismiss", scroll = false)
        compose.onNodeWithText("Password reset preview").assertDoesNotExist()
        replace("Password", "Shade before midday")
        compose.onNodeWithTag("auth_password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertPasswordRendering(compose.onNodeWithTag("auth_password"), "Shade before midday", visible = false)
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onNodeWithTag("auth_password").assertTextContains("Shade before midday")
        assertPasswordRendering(compose.onNodeWithTag("auth_password"), "Shade before midday", visible = true)
        top()
        capture("17_password.png")
        compose.onNodeWithContentDescription("Hide password").performScrollTo().performClick()
        assertPasswordRendering(compose.onNodeWithTag("auth_password"), "Shade before midday", visible = false)
        click("Create account")
        val passwordCorrection = "Use at least 12 characters."
        val passwordError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        compose.onNodeWithTag("auth_password").assert(passwordError.not())
        compose.onNodeWithTag("app_title").assertTextEquals("Create account")
        top()
        capture("02_signup.png")
        replace("Password", "shade")
        compose.onNodeWithText(passwordCorrection).assertExists()
        compose.onNodeWithTag("auth_password").assert(passwordError)
        compose.onNodeWithText("Create demo account").assertIsNotEnabled()
        replace("Password", "Shade before midday")
        compose.onNodeWithTag("auth_password").assert(passwordError.not())
        compose.onNodeWithText("Create demo account").assertIsEnabled()
        click("Create demo account")

        // Both availability branches retain the same navigation and avoid fake data.
        bottomNav("Today")
        compose.onNodeWithText("Check-in due").assertIsDisplayed()
        capture("03_today.png")
        setConditionsUnavailable()
        top()
        compose.onNodeWithText("Conditions unavailable").assertIsDisplayed()
        compose.onNodeWithText("Sample unavailable").assertExists()
        capture("16_unavailable.png")
        click("Review")
        click("Compare work windows")
        compose.onNodeWithText("No comparison available").assertExists()
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        capture("22_unavailable_plan.png")
        click("Return to Today")
        click("Retry forecast")
        compose.onNodeWithText("Conditions unavailable").assertDoesNotExist()

        // Actual date/time/dropdown overlays, followed by a persisted plan choice.
        click("Review")
        bottomNav("Today")
        compose.onNodeWithTag("app_title").assertTextEquals("Shift plan")
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
        compose.onNodeWithText("Pruning").performScrollTo().assertIsDisplayed()
        capture("15_dropdown.png")
        choose("Pruning")
        compose.onNodeWithText("Pruning").assertExists()
        openChoice("Work type")
        choose("Planting beds")
        click("Compare work windows")
        top()
        compose.onNodeWithText("Air temperature").assertIsDisplayed()
        reach(hasText("24–27°C · UV 3–5")).assertIsDisplayed()
        reach(hasText("31–34°C · UV 7–8")).assertIsDisplayed()
        reach(hasText("Heavy effort or protective clothing makes a cooler work arrangement especially relevant.")).assertIsDisplayed()
        capture("12_compare.png")
        reach(hasText("Save preferred window")).assertIsDisplayed()
        capture("21_plan_decision.png")
        click("12:00–14:00")
        click("Save preferred window")
        reach(hasText("Planting beds · 12:00–14:00")).assertIsDisplayed()
        // Restore the preferred window used in the initial screenshot set.
        click("Review")
        click("Compare work windows")
        click("08:00–10:00")
        click("Save preferred window")
        reach(hasText("Planting beds · 08:00–10:00")).assertIsDisplayed()

        // Records, detail and edit share one entity rather than isolated mock screens.
        tab("Log")
        compose.onNodeWithText("6 sessions").assertExists()
        capture("05_records.png")
        click("Birrarung Marr · 12 Jan 2026")
        bottomNav("Log")
        compose.onNodeWithText("Session detail").assertIsDisplayed()
        capture("06_detail.png")
        click("Edit record")
        compose.onNodeWithTag("app_title").assertTextEquals("Edit record")
        capture("07_edit.png")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        bottomNav("Log")
        click("Search")
        bottomNav("Log")
        compose.onNodeWithTag("app_title").assertTextEquals("Search records")
        capture("08_search.png")

        // Text + status + task + date predicates are AND-combined, then reset.
        replace("Work type or site", "Birrarung")
        reach(hasText("4 results")).assertIsDisplayed()
        clickMatching(hasText("Filters", substring = true))
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
        click("Newest first")
        choose("Longest outdoors")
        compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction())
            .onFirst().assert(hasText("140 min outdoors · 10 min breaks"))
        click("Birrarung Marr · 9 Jan 2026")
        compose.onNodeWithText("140").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        bottomNav("Log")

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
        compose.onNodeWithText("Can you take a cooler pause?").assertIsDisplayed()
        capture("10_break.png")
        click("Cannot pause yet")
        compose.onNodeWithText("Discuss a lighter task or a cooler location with your supervisor. Your follow-up will stay open.").assertExists()
        click("Record this response")
        top()
        compose.onNodeWithText("Follow-up remains open").assertExists()
        capture("18_break_response.png")
        click("Return to Today")
        compose.onNodeWithText("Follow-up needed").assertIsDisplayed()
        tab("Trends")
        totals(610, 95)
        tab("Log")
        click("Birrarung Marr · 12 Jan 2026")
        reach(hasText("Cannot pause yet. A work adjustment needs discussion. The follow-up remains open.")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        tab("Profile")
        bottomNav("Profile")
        compose.onNodeWithText("Work preferences").assertIsDisplayed()
        capture("11_profile.png")
        val reminderSwitch = compose.onNodeWithContentDescription("Break check-in reminders")
        reminderSwitch.performScrollTo().assertIsOn().performClick().assertIsOff()
        reminderSwitch.performClick().assertIsOn()
        click("Save demo preferences")
        reach(hasText("Saved on this screen.")).assertIsDisplayed()
        compose.onNodeWithTag("profile_site").assertTextContains("Birrarung Marr")
        capture("20_preferences.png")

        // CRUD runs after screenshots so submitted fixture captures stay coherent.
        tab("Log")
        click("Add record")
        compose.onNodeWithTag("app_title").assertTextEquals("Add record")
        click("Add record")
        reach(hasText("Enter 1–960 minutes of outdoor work.")).assertIsDisplayed()
        compose.onNodeWithTag("record_outdoor").assertIsFocused()
        hideKeyboard()
        capture("19_form_error.png")
        replace("Outdoor work · required", "40")
        replace("Logged breaks · required", "10")
        click("Add record")
        compose.onNodeWithText("7 sessions").assertExists()
        tab("Trends")
        totals(650, 105)
        tab("Log")
        clickMatching(hasText("Planting beds") and hasText("40 min outdoors · 10 min breaks"))
        click("Edit record")
        replace("Outdoor work · required", "45")
        replace("Logged breaks · required", "12")
        click("Save changes")
        compose.onNodeWithText("45").assertExists()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        tab("Trends")
        totals(655, 107)
        tab("Log")
        clickMatching(hasText("Planting beds") and hasText("45 min outdoors · 12 min breaks"))
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
        assertEquals(22, File(screenshots, "capture-manifest.csv").readLines().size - 1)
    }

    private fun selectable(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun tab(label: String) {
        compose.onNode(selectable(label)).performClick()
        compose.waitForIdle()
        bottomNav(label)
    }

    private fun bottomNav(selected: String) {
        listOf("Today", "Log", "Trends", "Profile").forEach {
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

    private fun top() {
        compose.onNodeWithTag("page_content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
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

    /** Fixture setup only: the removed preview control is not a user workflow. */
    private fun setConditionsUnavailable() {
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[PrototypeViewModel::class.java].contextUnavailable = true
        }
        compose.waitForIdle()
    }

    private fun choose(option: String) {
        // Popup options occur after any equally labelled fields/cards underneath.
        compose.onAllNodes(hasText(option) and hasClickAction()).onLast().performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun replace(label: String, value: String) {
        reach(hasText(label) and hasSetTextAction()).performTextReplacement(value)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun totals(outdoor: Int, breaks: Int) {
        top()
        compose.onNodeWithText(outdoor.toString()).assertIsDisplayed()
        compose.onNodeWithText(breaks.toString()).assertIsDisplayed()
    }

    private fun capture(filename: String) {
        compose.waitForIdle()
        // Semantics can settle before RenderThread submits the new screen. Await
        // an actual committed frame before presentation. Final images still need
        // visual review because popups can use a separate platform surface.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val committed = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                decor.invalidate()
            }
            assertTrue("Android frame was not committed for $filename", committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        Thread.sleep(300) // Let the committed surface and platform popup become visible.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(screenshots, filename).outputStream().use {
            assertTrue("PNG capture failed: $filename", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        File(screenshots, "capture-manifest.csv").appendText("$filename,${System.currentTimeMillis()}\n")
    }
}
