package heatshield

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import heatshield.ui.PrototypeViewModel

/**
 * Runs the installed MainActivity. The caller configures the emulator's actual
 * window and font scale; this test never renders a substitute size or app.
 *
 * Arguments: designCase, expectedWidthDp, expectedFontScale, captureDirectory.
 * Captures stay under this app's external-files directory, separate from the
 * proposal screenshots. Geometry/semantics assertions complement visual review;
 * they do not establish TalkBack usability or WCAG conformance.
 */
@RunWith(AndroidJUnit4::class)
class DesignQualityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val args get() = InstrumentationRegistry.getArguments()
    private val automation get() = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val observations = JSONArray()
    private val capturedScreens = linkedSetOf<String>()
    private var captureNumber = 0
    private val output: File by lazy {
        val base = requireNotNull(compose.activity.getExternalFilesDir(null)).canonicalFile
        val case = args.getString("designCase", "unspecified").orEmpty().replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val relative = args.getString("captureDirectory", "design-quality/$case") ?: "design-quality/$case"
        File(base, relative).canonicalFile.also {
            require(it.path.startsWith(base.path + File.separator)) { "Capture directory must stay inside app external files" }
            require(it.mkdirs() || it.isDirectory)
        }
    }

    @Test
    fun captureAllScreensAtConfiguredWindow() {
        verifyEnvironment()
        page("S01_login")
        fun passwordField() = compose.onNodeWithTag("auth_password")
        passwordField().performScrollTo().performTextReplacement("Shade before midday")
        hideKeyboard()
        // Re-resolve after the IME/layout transition instead of retaining an
        // interaction that may point at the field's previous semantics node.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("auth_password").fetchSemanticsNodes().size == 1
        }
        passwordField().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertPasswordRendering(passwordField(), "Shade before midday", visible = false)
        clickDescription("Show password")
        assertPasswordRendering(passwordField(), "Shade before midday", visible = true)
        passwordField().assertTextContains("Shade before midday")
        capture("password_visible")
        clickDescription("Hide password")
        assertPasswordRendering(passwordField(), "Shade before midday", visible = false)
        click("Forgot password?")
        compose.onNodeWithText("Password reset preview").assertIsDisplayed()
        capture("password_reset")
        click("Dismiss", false)
        click("Create account")
        page("S02_signup")
        compose.onNodeWithTag("auth_password").performScrollTo().performTextReplacement("shade")
        hideKeyboard()
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.onNodeWithText("Create demo account").assertIsNotEnabled()
        capture("signup_error")
        click("Explore demo")

        nav("today")
        page("S03_today")
        click("Review")
        page("S04_plan")
        clickMatching(hasText("Work date ·", substring = true))
        compose.onNodeWithText("Apply date").assertIsDisplayed()
        capture("date_picker")
        click("Apply date", false)
        clickMatching(hasText("Shift ·", substring = true))
        compose.onNodeWithText("Apply time").assertIsEnabled()
        capture("time_picker")
        click("Apply time", false)
        openChoice("Work type")
        compose.onNodeWithText("Pruning").performScrollTo().assertIsDisplayed()
        capture("work_dropdown")
        choose("Planting beds")
        click("Compare work windows")
        page("comparison")
        click("08:00–10:00")
        click("Save preferred window")

        nav("records")
        page("S05_records")
        click("Birrarung Marr · 12 Jan 2026")
        page("S06_detail")
        click("Edit record")
        page("S07_edit")
        back(); back()
        click("Add record")
        click("Add record")
        reach(hasText("Enter 1–960 minutes of outdoor work.")).assertIsDisplayed()
        compose.onNodeWithTag("record_outdoor").assertIsFocused()
        hideKeyboard()
        page("record_error")
        back()
        click("Search")
        page("S08_search")
        clickMatching(hasText("Filters", substring = true))
        page("search_filters")
        click("Any date")
        compose.onNodeWithText("Use date").assertIsDisplayed()
        capture("search_date_picker")
        click("Cancel", false)
        openChoice("Follow-up")
        capture("filter_dropdown")
        choose("Needs review")
        reach(hasText("2 results")).assertIsDisplayed()
        replaceTag("search_query", "No matching worksite")
        reach(hasText("No matching sessions")).assertIsDisplayed()
        capture("search_empty")
        click("Reset")

        nav("trends")
        page("S09_trends")
        click("Data values")
        page("chart_values")
        click("By week")
        reach(hasText("Logged minutes by week")).assertIsDisplayed()
        page("weekly_chart")

        nav("today")
        click("Check in now")
        page("S10_break")
        click("Cannot pause yet")
        click("Record this response")
        page("break_response")
        click("Return to Today")
        nav("profile")
        page("S11_profile")
        click("Save demo preferences")
        reach(hasText("Saved on this screen.")).assertIsDisplayed()
        capture("preferences_saved")
        nav("today")
        setConditionsUnavailable()
        page("unavailable_today")
        click("Review")
        click("Compare work windows")
        page("unavailable_comparison")
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        click("Return to Today")
        click("Retry forecast")

        val expected = setOf("S01_login", "S02_signup", "S03_today", "S04_plan", "S05_records", "S06_detail", "S07_edit", "S08_search", "S09_trends", "S10_break", "S11_profile")
        assertTrue("Missing screens: ${expected - capturedScreens}", capturedScreens.containsAll(expected))
        saveObservations()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun verifyKeyboardFocusAndSemantics() {
        verifyEnvironment()
        val email = compose.onNodeWithTag("auth_email")
        val password = compose.onNodeWithTag("auth_password")
        email.performScrollTo().performClick()
        email.assertIsFocused()
        compose.waitUntil(5_000) { imeVisible() }
        capture("login_keyboard_open")
        nativeAccessibilitySnapshot("login_keyboard", requireEditableFocus = true)
        email.performKeyInput { pressKey(Key.Tab) }
        password.assertIsFocused()
        password.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
        email.assertIsFocused()
        email.performImeAction()
        password.assertIsFocused()
        password.performTextReplacement("")
        password.performImeAction()
        password.assert(hasError())
        compose.onNodeWithTag("nav_today").assertDoesNotExist()
        capture("keyboard_inline_error")
        nativeAccessibilitySnapshot("password_error", requireError = true)
        password.performTextReplacement("Shade before midday")
        password.performImeAction()
        compose.onNodeWithTag("nav_today").assertIsSelected()
        nav("profile")
        val site = compose.onNodeWithTag("profile_site")
        site.performScrollTo().performClick()
        compose.waitUntil(5_000) { imeVisible() }
        site.performTextReplacement("Carlton Gardens")
        capture("profile_keyboard_open")
        nativeAccessibilitySnapshot("profile_keyboard", requireEditableFocus = true)
        Espresso.pressBack()
        compose.waitUntil(5_000) { !imeVisible() }
        site.assertTextContains("Carlton Gardens")
        compose.onNodeWithTag("nav_profile").assertIsSelected()
        click("Save demo preferences")
        reach(hasText("Saved on this screen.")).assertIsDisplayed()
        val reminder = reach(hasContentDescription("Break check-in reminders"))
        reminder.assertIsOn().performClick().assertIsOff()
        assertMinimumTarget(reminder, "Break check-in reminders")
        compose.onAllNodes(hasContentDescription("Break check-in reminders") and hasClickAction()).assertCountEquals(1)
        capture("keyboard_and_semantics_complete")
        nativeAccessibilitySnapshot("reminder_switch")

        nav("records")
        click("Search")
        val query = compose.onNodeWithTag("search_query")
        query.performScrollTo().performClick().assertIsFocused()
        compose.waitUntil(5_000) { imeVisible() }
        query.performTextReplacement("Carlton")
        nativeAccessibilitySnapshot("search_keyboard", requireEditableFocus = true)
        reach(hasText("2 results")).assertIsDisplayed()
        reach(hasText("Filters") and hasClickAction()).assertIsDisplayed()
        assertTrue("Search keyboard closed before the keyboard-state capture", imeVisible())
        capture("search_keyboard_open")
        click("Filters")
        reach(hasText("Work type")).assertIsDisplayed()
        assertTrue("Filters could not be reached with the Search keyboard open", imeVisible())
        capture("search_filters_keyboard_open")
        Espresso.pressBack()
        compose.waitUntil(5_000) { !imeVisible() }
        query.performScrollTo().assertTextContains("Carlton")
        compose.onNodeWithTag("app_title").assertTextEquals("Search records")
        clickDescription("Clear search")
        query.assert(SemanticsMatcher("Search field is empty") {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
        })
        reach(hasText("6 results")).assertIsDisplayed()
        capture("search_keyboard_dismissed_and_cleared")
        saveObservations()
    }

    @Test
    fun verifyCompactTimeKeyboardGeometry() {
        verifyEnvironment()
        val config = compose.activity.resources.configuration
        require(config.fontScale > 1.3f || config.screenHeightDp < 500) {
            "Run this case with large text or a window shorter than 500dp to exercise compact time controls"
        }
        val ui = automation
        val serviceInfo = ui.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = serviceInfo
        try {
            click("Explore demo")
            click("Review")
            clickMatching(hasText("Shift ·", substring = true))
            val hour = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog())).onFirst()
            if (!hour.isDisplayed()) hour.performScrollTo()
            hour.assertIsDisplayed().performClick().assertIsFocused()
            hour.performTextReplacement("8")

            fun imeScreenBounds(): Rect? = ui.windows
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
                ?.takeIf { !it.isEmpty }

            compose.waitUntil(5_000) { imeScreenBounds() != null }
            Thread.sleep(300) // Allow the native IME window's resize animation to finish.
            val imeBounds = requireNotNull(imeScreenBounds())
            val screenBounds = if (Build.VERSION.SDK_INT >= 30) compose.activity.windowManager.maximumWindowMetrics.bounds
                else compose.activity.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
            val buttonBounds = mutableMapOf<String, Rect>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 60) return
                val label = node.text?.toString() ?: node.contentDescription?.toString()
                if (node.packageName?.toString() == compose.activity.packageName &&
                    node.isVisibleToUser && label in listOf("Apply time", "Cancel")) {
                    var target = node
                    while (!target.isClickable) {
                        val parent = target.parent ?: break
                        target = parent
                    }
                    assertTrue("$label has no enabled native click target", target.isClickable && target.isEnabled)
                    buttonBounds[requireNotNull(label)] = Rect().also { target.getBoundsInScreen(it) }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            }
            ui.windows.mapNotNull { it.root }.forEach { visit(it, 0) }
            val geometry = JSONObject().put("imeBoundsInScreenPx", imeBounds.toShortString())
                .put("screenBoundsPx", screenBounds.toShortString())
                .put("buttonsInScreenPx", JSONObject(buttonBounds.mapValues { it.value.toShortString() }))
                .put("scope", "Native screen coordinates for complete clickable button bounds while an actual IME window is present.")
            File(output, "time_keyboard_geometry.json").writeText(geometry.toString(2))
            capture("compact_time_keyboard_open")
            listOf("Apply time", "Cancel").forEach { label ->
                val bounds = requireNotNull(buttonBounds[label]) { "$label missing from native accessibility windows: $geometry" }
                assertTrue("$label has empty/offscreen bounds: $geometry", !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0)
                assertTrue("$label overlaps the keyboard: $geometry", bounds.bottom <= imeBounds.top)
                assertTrue("$label exceeds the screen width: $geometry", bounds.right <= screenBounds.right)
            }
            // A real pointer tap complements the geometry check; semantic clicks
            // alone could dispatch an action through an obscuring keyboard.
            compose.onNode(hasText("Apply time") and hasClickAction()).performTouchInput { click() }
            compose.onNodeWithText("Shift start").assertDoesNotExist()
            reach(hasText("Shift · 08:00–15:00")).assertIsDisplayed()
            saveObservations()
        } finally {
            serviceInfo.flags = originalFlags
            ui.serviceInfo = serviceInfo
        }
    }

    @Test
    fun verifyAdditionalBranchesAndEmptyStates() {
        verifyEnvironment()
        replaceTag("auth_email", "invalid")
        replaceTag("auth_password", "")
        click("Log in to demo")
        compose.onNodeWithTag("auth_email").assert(hasError())
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.onNodeWithTag("nav_today").assertDoesNotExist()
        capture("invalid_login")
        click("Explore demo")

        nav("profile")
        replaceTag("profile_site", "")
        click("Save demo preferences")
        compose.onNodeWithTag("profile_site").assert(hasError())
        compose.onNodeWithText("Saved on this screen.").assertDoesNotExist()
        replaceTag("profile_site", "Carlton Gardens")
        click("Save demo preferences")
        reach(hasText("Saved on this screen.")).assertIsDisplayed()

        nav("today")
        click("Check in now")
        click("No cooler place available")
        click("Record this response")
        reach(hasText("Follow-up remains open")).assertIsDisplayed()
        capture("no_cooler_place_response")
        // Confirmation must commit before any navigation, including the app bar Back.
        back()
        reach(hasText("Follow-up needed")).assertIsDisplayed()
        reach(hasText("No cooler place available", substring = true)).assertIsDisplayed()
        nav("records")
        click("Birrarung Marr · 12 Jan 2026")
        reach(hasText("No cooler place available. A work adjustment needs discussion. The follow-up remains open.")).assertIsDisplayed()
        reach(hasText("Needs review")).assertIsDisplayed()
        capture("deferred_response_persisted_after_back")
        back()
        nav("today")
        click("Check in now")
        click("Cooler place available")
        click("Start break preview")
        reach(hasText("00:00")).assertIsDisplayed()
        capture("break_started")
        click("Change my response")
        compose.onNodeWithText("Finish break preview").assertDoesNotExist()
        click("Start break preview")
        click("Finish break preview")
        reach(hasText("Break check-in completed")).assertIsDisplayed()
        capture("break_completed")
        // Switching destinations must also preserve the completed response.
        nav("records")
        click("Birrarung Marr · 12 Jan 2026")
        val note = "Break check-in completed. No duration was added."
        reach(hasText(note)).assertIsDisplayed()
        reach(hasText("Reviewed")).assertIsDisplayed()
        capture("completed_response_persisted_after_navigation")
        back()
        nav("today")
        // Bottom navigation restores Today's saved secondary stack. The
        // confirmed Break screen may therefore reopen before its parent.
        if (compose.onAllNodes(hasTestTag("app_title") and hasText("Break check-in")).fetchSemanticsNodes().isNotEmpty()) back()
        reach(hasText("Check-in recorded")).assertIsDisplayed()

        nav("records")
        click("Birrarung Marr · 12 Jan 2026")
        reach(hasText(note)).assertIsDisplayed()
        click("Edit record")
        replaceTag("record_outdoor", "0")
        click("Save changes")
        compose.onNodeWithTag("record_outdoor").assert(hasError())
        compose.onNodeWithTag("record_breaks").assertTextContains("15")
        replaceTag("record_outdoor", "961")
        click("Save changes")
        compose.onNodeWithTag("record_outdoor").assert(hasError())
        replaceTag("record_outdoor", "95")
        replaceTag("record_breaks", "481")
        click("Save changes")
        compose.onNodeWithTag("record_breaks").assert(hasError())
        capture("numeric_boundaries")
        replaceTag("record_breaks", "15")
        replaceTag("record_outdoor", "-1")
        compose.onNodeWithTag("record_outdoor").assertTextContains("95")
        replaceTag("record_outdoor", "abc")
        compose.onNodeWithTag("record_outdoor").assertTextContains("95")
        click("Save changes")
        reach(hasText(note)).assertIsDisplayed()
        back()
        nav("trends")
        reach(hasText("610")).assertIsDisplayed()
        reach(hasText("95")).assertIsDisplayed()

        nav("records")
        val records = listOf(
            "Birrarung Marr · 12 Jan 2026", "Carlton Gardens · 11 Jan 2026",
            "Birrarung Marr · 10 Jan 2026", "Birrarung Marr · 9 Jan 2026",
            "Carlton Gardens · 8 Jan 2026", "Birrarung Marr · 7 Jan 2026"
        )
        records.forEachIndexed { index, label ->
            click(label)
            click("Delete record")
            compose.onNode(hasText("Delete record") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            reach(hasText("${5 - index} sessions")).assertIsDisplayed()
            if (index == 0) {
                nav("today")
                reach(hasText("No active session")).assertIsDisplayed()
                click("Open work records")
                compose.onNodeWithTag("nav_records").assertIsSelected()
            }
        }
        reach(hasText("No work records yet")).assertIsDisplayed()
        capture("records_empty")
        nav("trends")
        reach(hasText("No sessions in this view")).assertIsDisplayed()
        compose.onAllNodesWithText("0").assertCountEquals(2)
        capture("trends_empty")
        nav("profile")
        click("Leave demo")
        compose.onNodeWithTag("nav_today").assertDoesNotExist()
        replaceTag("auth_email", "worker@example.com")
        replaceTag("auth_password", "Shade before midday")
        click("Log in to demo")
        compose.onNodeWithTag("nav_today").assertIsSelected()
        saveObservations()
    }

    @Test
    fun verifySavedPlanContext() {
        verifyEnvironment()
        click("Explore demo")
        click("Review")
        openChoice("Work type"); choose("Mowing")
        openChoice("Effort"); choose("Light effort")
        openChoice("Protective clothing"); choose("Heavy protective clothing")
        click("Compare work windows")
        reach(hasText("Mowing · Light effort")).assertIsDisplayed()
        // PPE still changes the explanation when effort alone is light.
        reach(hasText("Heavy effort or protective clothing makes a cooler work arrangement especially relevant.")).assertIsDisplayed()
        click("12:00–14:00")
        click("Save preferred window")
        reach(hasText("Mowing · 12:00–14:00")).assertIsDisplayed()
        capture("saved_plan_summary")
        click("Review")
        reach(hasText("Work type")).assertTextContains("Mowing")
        reach(hasText("Effort")).assertTextContains("Light effort")
        reach(hasText("Protective clothing")).assertTextContains("Heavy protective clothing")
        click("Compare work windows")
        reach(hasText("Mowing · Light effort")).assertIsDisplayed()
        reach(hasText("12:00–14:00") and isSelectable()).assertIsSelected()
        capture("saved_plan_context_reopened")
        saveObservations()
    }

    @Test
    fun verifyUnavailableDateBoundary() {
        verifyEnvironment()
        click("Explore demo")
        click("Review")
        clickMatching(hasText("Work date ·", substring = true))
        calendarSnapshot("calendar_semantics")
        // Exact full-date button labels verified in calendar_semantics.json.
        compose.onNode(hasText("Tuesday, January 13, 2026") and hasClickAction(), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
            .performTouchInput { click() }
        click("Apply date", false)
        reach(hasText("Work date ·", substring = true) and hasText("13 Jan 2026", substring = true)).assertIsDisplayed()
        click("Compare work windows")
        reach(hasText("No comparison available")).assertIsDisplayed()
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        capture("date_outside_fixture")
        click("Change work context")
        clickMatching(hasText("Work date ·", substring = true))
        calendarSnapshot("calendar_semantics_restore")
        compose.onNode(hasText("Monday, January 12, 2026") and hasClickAction(), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
            .performTouchInput { click() }
        click("Apply date", false)
        reach(hasText("Work date ·", substring = true) and hasText("12 Jan 2026", substring = true)).assertIsDisplayed()
        click("Compare work windows")
        reach(hasText("Save preferred window")).assertIsEnabled()
        click("Save preferred window")
        setConditionsUnavailable()
        reach(hasText("Conditions unavailable")).assertIsDisplayed()
        click("Review")
        click("Compare work windows")
        reach(hasText("No comparison available")).assertIsDisplayed()
        compose.onNodeWithText("Save preferred window").assertDoesNotExist()
        capture("conditions_unavailable")
        click("Return to Today")
        click("Retry forecast")
        compose.onNodeWithText("Conditions unavailable").assertDoesNotExist()
        saveObservations()
    }

    private fun calendarSnapshot(name: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(SemanticsMatcher("Every semantic node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        val labelled = JSONArray()
        val snapshot = JSONArray()
        nodes.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val item = JSONObject().put("id", node.id).put("parentId", node.parent?.id)
                .put("children", JSONArray(node.children.map { it.id }))
                .put("texts", JSONArray(texts)).put("contentDescriptions", JSONArray(descriptions))
                .put("boundsInRoot", node.boundsInRoot.toString()).put("config", node.config.toString())
            snapshot.put(item)
            if (texts.isNotEmpty() || descriptions.isNotEmpty()) labelled.put(item)
        }
        File(output, "$name.json").writeText(JSONObject().put("labelledNodes", labelled)
            .put("unmergedSemanticSnapshot", snapshot).toString(2))
        capture(name)
    }

    /** Fixture setup only: all blocked/retry behaviour is still asserted through the UI. */
    private fun setConditionsUnavailable() {
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[PrototypeViewModel::class.java].contextUnavailable = true
        }
        compose.waitForIdle()
    }

    /** Read both measured native window bounds and resource configuration. */
    private fun verifyEnvironment() {
        compose.waitForIdle()
        var measuredWidth = 0
        var measuredHeight = 0
        compose.runOnUiThread {
            measuredWidth = compose.activity.window.decorView.width
            measuredHeight = compose.activity.window.decorView.height
        }
        val resources = compose.activity.resources
        val density = resources.displayMetrics.density
        val fontScale = resources.configuration.fontScale
        val widthDp = measuredWidth / density
        require(measuredWidth > 0 && measuredHeight > 0) { "Activity has no measured window" }
        args.getString("expectedWidthDp")?.toFloat()?.let {
            assertTrue("Measured window ${widthDp}dp differs from requested ${it}dp", abs(widthDp - it) <= 2f)
        }
        args.getString("expectedFontScale")?.toFloat()?.let {
            assertTrue("Actual fontScale $fontScale differs from requested $it", abs(fontScale - it) <= .03f)
        }
        val nativeBounds = if (Build.VERSION.SDK_INT >= 30) compose.activity.windowManager.currentWindowMetrics.bounds else null
        // Acquiring automation must not switch off a TalkBack service the caller enabled.
        automation.serviceInfo
        val accessibility = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { it.resolveInfo.serviceInfo.packageName }
        if (args.getString("expectTalkBack") == "true") {
            assertTrue("TalkBack was requested but is not enabled: $services", services.contains("com.google.android.marvin.talkback"))
        }
        val json = JSONObject()
            .put("case", args.getString("designCase", "unspecified"))
            .put("measuredDecorWidthPx", measuredWidth).put("measuredDecorHeightPx", measuredHeight)
            .put("measuredWidthDp", widthDp).put("measuredHeightDp", measuredHeight / density)
            .put("density", density).put("densityDpi", resources.displayMetrics.densityDpi)
            .put("fontScale", fontScale).put("configurationWidthDp", resources.configuration.screenWidthDp)
            .put("configurationHeightDp", resources.configuration.screenHeightDp)
            .put("nativeWindowBounds", nativeBounds?.toShortString())
            .put("enabledAccessibilityServicePackages", JSONArray(services))
            .put("limitations", "Real Activity at configured window. Semantics and layout checks are not a TalkBack or complete WCAG assessment.")
        File(output, "environment.json").writeText(json.toString(2))
    }

    /** Capture every reachable vertical viewport, rather than only its first fold. */
    private fun page(name: String) {
        capturedScreens.add(name)
        val content = compose.onNodeWithTag("page_content")
        content.assertExists()
        scrollPage(-100_000f)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            .let { assertTrue("No heading semantics on $name", it.isNotEmpty()) }
        var previous = Float.NaN
        for (step in 0 until 16) {
            val node = content.fetchSemanticsNode()
            val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val value = range?.value?.invoke() ?: 0f
            // LazyColumn encodes within-item offsets as small fractions; do not
            // mistake a real scroll inside a tall form item for no movement.
            if (step > 0 && value == previous) return
            capture("${name}_$step")
            if (range == null || value >= range.maxValue()) return
            previous = value
            scrollPage(node.boundsInRoot.height * .7f)
        }
        throw AssertionError("$name did not reach the bottom within 16 viewports")
    }

    private fun scrollPage(pixels: Float) {
        val content = compose.onNodeWithTag("page_content")
        if (content.fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy)) {
            content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
            compose.waitForIdle()
        }
    }

    private fun auditVisibleText(captureName: String) {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        var checked = 0
        var allocationOverflowOnly = 0
        nodes.forEach { node ->
            var ancestor = node.parent
            var editable = node.config.contains(SemanticsActions.SetText)
            while (ancestor != null) {
                editable = editable || ancestor.config.contains(SemanticsActions.SetText)
                ancestor = ancestor.parent
            }
            if (editable || node.boundsInRoot.width <= 0 || node.boundsInRoot.height <= 0) return@forEach
            val interaction = compose.onNode(SemanticsMatcher("Node ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            if (!interaction.isDisplayed()) return@forEach
            val layouts = mutableListOf<TextLayoutResult>()
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                val violations = mutableListOf<String>()
                // Paragraph width can retain the parent's allocation after Text
                // wraps its own measured width to content. Centred lines can
                // retain that allocation's alignment offset too, so compare
                // line widths rather than absolute paragraph x-coordinates.
                // Independently rounded endpoints can accumulate one physical
                // pixel of difference. This limit does not scale with density.
                val roundingPx = 1.0f
                if (layout.multiParagraph.didExceedMaxLines) violations.add("Maximum line count exceeded")
                for (line in 0 until layout.lineCount) {
                    if (layout.isLineEllipsized(line)) violations.add("Line $line is ellipsized")
                    if (layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + roundingPx) {
                        violations.add("Line $line is wider than the measured text layout")
                    }
                    if (layout.getLineTop(line) < -roundingPx || layout.getLineBottom(line) > layout.size.height + roundingPx) {
                        violations.add("Line $line extends beyond measured height")
                    }
                }
                val textLength = layout.layoutInput.text.length
                if (textLength > 0 && (layout.lineCount == 0 || layout.getLineStart(0) != 0 ||
                        layout.getLineEnd(layout.lineCount - 1) != textLength)) {
                    violations.add("Rendered lines do not cover the complete input text")
                }
                if (violations.isNotEmpty()) {
                    val lines = JSONArray()
                    for (line in 0 until layout.lineCount) {
                        lines.put(JSONObject().put("index", line)
                            .put("topPx", layout.getLineTop(line)).put("bottomPx", layout.getLineBottom(line))
                            .put("leftPx", layout.getLineLeft(line)).put("rightPx", layout.getLineRight(line))
                            .put("widthPx", layout.getLineRight(line) - layout.getLineLeft(line))
                            .put("start", layout.getLineStart(line)).put("end", layout.getLineEnd(line))
                            .put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                            .put("ellipsized", layout.isLineEllipsized(line)))
                    }
                    val input = layout.layoutInput
                    val bounds = node.boundsInRoot
                    val diagnostics = JSONObject().put("capture", captureName).put("nodeId", node.id)
                        .put("violations", JSONArray(violations)).put("roundingTolerancePhysicalPx", roundingPx)
                        .put("text", input.text.text)
                        .put("sizeWidthPx", layout.size.width).put("sizeHeightPx", layout.size.height)
                        .put("paragraphWidthPx", layout.multiParagraph.width).put("paragraphHeightPx", layout.multiParagraph.height)
                        .put("widthExcessPx", layout.multiParagraph.width - layout.size.width)
                        .put("heightExcessPx", layout.multiParagraph.height - layout.size.height)
                        .put("didOverflowWidth", layout.didOverflowWidth).put("didOverflowHeight", layout.didOverflowHeight)
                        .put("didExceedMaxLines", layout.multiParagraph.didExceedMaxLines).put("lineCount", layout.lineCount)
                        .put("minWidthPx", input.constraints.minWidth).put("maxWidthPx", input.constraints.maxWidth)
                        .put("minHeightPx", input.constraints.minHeight).put("maxHeightPx", input.constraints.maxHeight)
                        .put("maxLines", input.maxLines).put("softWrap", input.softWrap).put("overflow", input.overflow.toString())
                        .put("fontSize", input.style.fontSize.toString()).put("lineHeight", input.style.lineHeight.toString())
                        .put("density", input.density.density).put("fontScale", input.density.fontScale)
                        .put("boundsInRoot", JSONObject().put("left", bounds.left).put("top", bounds.top)
                            .put("right", bounds.right).put("bottom", bounds.bottom)
                            .put("width", bounds.width).put("height", bounds.height))
                        .put("lines", lines)
                    File(output, "text_layout_failure_%03d.json".format(captureNumber)).writeText(diagnostics.toString(2))
                    throw AssertionError("Text overflow in $captureName: $diagnostics")
                }
                if (layout.hasVisualOverflow) allocationOverflowOnly++
                checked++
            }
        }
        observations.put(JSONObject().put("capture", captureName).put("visibleTextLayoutsChecked", checked)
            .put("allocationOverflowWithCompleteLines", allocationOverflowOnly)
            .put("textCheck", "Full character coverage, no ellipsis or max-line loss, line width and vertical extents within measured size plus 1 physical px for independently rounded endpoints. Screen position needs screenshot review."))
    }

    private fun assertMinimumTarget(node: SemanticsNodeInteraction, name: String) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("$name target is ${bounds.width / density} x ${bounds.height / density}dp", bounds.width / density >= 47.5f && bounds.height / density >= 47.5f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = java.util.concurrent.CountDownLatch(1)
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                decor.invalidate()
            }
            assertTrue("Android frame was not committed for $name", committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        Thread.sleep(300) // RenderThread submission precedes SurfaceFlinger presentation.
        val bitmap = requireNotNull(automation.takeScreenshot())
        val filename = "%03d_%s.png".format(++captureNumber, name)
        File(output, filename).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        try {
            auditVisibleText(name)
            observations.getJSONObject(observations.length() - 1)
                .put("filename", filename).put("capturedAtEpochMs", System.currentTimeMillis())
        } catch (failure: AssertionError) {
            observations.put(JSONObject().put("capture", name).put("filename", filename)
                .put("layoutFailure", failure.message))
            throw failure
        } finally {
            saveObservations()
        }
    }

    private fun saveObservations() {
        File(output, "observations.json").writeText(JSONObject().put("screens", JSONArray(capturedScreens.toList())).put("captures", observations).toString(2))
    }

    /** Inspect Android's exported accessibility nodes, without fabricating spoken-output evidence. */
    private fun nativeAccessibilitySnapshot(name: String, requireError: Boolean = false, requireEditableFocus: Boolean = false) {
        compose.waitForIdle()
        val nodes = JSONArray()
        var errors = 0
        var focusedEditable = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60) return
            if (node.packageName?.toString() == compose.activity.packageName) {
                if (!node.error.isNullOrBlank()) errors++
                if (node.isEditable && node.isFocused) focusedEditable++
                nodes.put(JSONObject().put("class", node.className?.toString())
                    .put("editable", node.isEditable).put("focused", node.isFocused)
                    .put("password", node.isPassword).put("checkable", node.isCheckable)
                    .put("checked", node.isChecked).put("clickable", node.isClickable)
                    .put("visible", node.isVisibleToUser).put("error", node.error?.toString())
                    .put("description", node.contentDescription?.toString())
                    .put("text", if (node.isEditable || node.isPassword) "[field value omitted]" else node.text?.toString()))
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
        roots.forEach { visit(it, 0) }
        assertTrue("No app nodes in the native accessibility tree", nodes.length() > 0)
        File(output, "native_$name.json").writeText(JSONObject().put("nodes", nodes)
            .put("nodesWithError", errors).put("focusedEditableNodes", focusedEditable)
            .put("scope", "Native accessibility exposure only; spoken output and TalkBack navigation require separate observation.").toString(2))
        if (requireError) assertTrue("Field error is absent from native accessibility nodes", errors > 0)
        if (requireEditableFocus) assertTrue("No focused editable field in native accessibility nodes", focusedEditable > 0)
    }

    private fun hasError() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun hideKeyboard() {
        compose.runOnUiThread {
            val activity = compose.activity
            val focused = activity.currentFocus
            val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(focused?.windowToken ?: activity.window.decorView.windowToken, 0)
            focused?.clearFocus()
        }
        compose.waitUntil(5_000) { !imeVisible() }
        compose.waitForIdle()
    }
    private fun reach(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitForIdle()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        return compose.onNode(matcher).performScrollTo()
    }
    private fun click(text: String, scroll: Boolean = true) = clickMatching(hasText(text), scroll)
    private fun clickMatching(matcher: SemanticsMatcher, scroll: Boolean = true) {
        val actionable = matcher and hasClickAction()
        val node = if (scroll) reach(actionable) else compose.onNode(actionable)
        node.performClick()
        compose.waitForIdle()
    }
    private fun clickDescription(description: String) = clickMatching(hasContentDescription(description))
    private fun nav(route: String) {
        compose.onNodeWithTag("nav_$route").performClick().assertIsSelected()
        listOf("today", "records", "trends", "profile").forEach {
            val node = compose.onNodeWithTag("nav_$it").assertIsDisplayed()
            assertMinimumTarget(node, "nav_$it")
        }
        compose.waitForIdle()
    }
    private fun back() { compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle() }
    private fun openChoice(label: String) { reach(hasText(label)).performClick(); compose.waitForIdle() }
    private fun choose(option: String) { compose.onAllNodes(hasText(option) and hasClickAction()).onLast().performScrollTo().assertIsDisplayed().performClick(); compose.waitForIdle() }
    private fun replaceTag(tag: String, value: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value); hideKeyboard() }
}

/** KeyboardType.Password can correctly retain password semantics while text is shown. */
internal fun assertPasswordRendering(node: SemanticsNodeInteraction, value: String, visible: Boolean) {
    val layouts = mutableListOf<TextLayoutResult>()
    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertTrue("Password field did not expose its rendered text layout", layouts.isNotEmpty())
    val expected = if (visible) value else "\u2022".repeat(value.length)
    assertTrue("Password rendering does not match the ${if (visible) "visible" else "masked"} state",
        layouts.any { it.layoutInput.text.text == expected })
}
