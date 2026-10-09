package heatshield

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.data.BreakReason
import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.MelbourneZone
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.ShiftTimesEdit
import heatshield.data.WorkContext
import heatshield.data.WorkSession
import heatshield.data.WorkSite
import android.text.format.DateFormat
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.UserPreferences
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatProfile
import heatshield.domain.NextStep
import heatshield.domain.OpenAnswerKind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import heatshield.domain.PersonalFactors
import heatshield.domain.SourceLabel
import heatshield.ui.*
import java.time.Clock
import java.time.LocalTime
import org.junit.Assert.assertNotNull
import heatshield.ui.HeatShieldTheme
import heatshield.ui.LoginScreen
import heatshield.ui.RecordsScreen
import heatshield.ui.SearchScreen
import heatshield.ui.SessionDetailScreen
import heatshield.ui.ShiftPlanScreen
import heatshield.ui.SignUpScreen
import heatshield.ui.TrendsScreen
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User-facing form, planning and record workflows; persistence is covered separately. */
@RunWith(AndroidJUnit4::class)
class UserInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun loginValidatesCredentialsAndUserControlsPasswordVisibility() {
        var credentials: Pair<String, String>? = null
        compose.setEnglishContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { email, password -> credentials = email to password },
                    onSignUp = {}, onReset = {}, configured = true,
                    busy = false, message = null
                )
            }
        }
        click("Sign in")
        compose.onNodeWithTag("auth_email").assert(hasError())
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.runOnIdle { assertEquals(null, credentials) }
        replace("auth_email", " worker@example.com ")
        replace("auth_password", "A careful long passphrase")
        compose.onNodeWithTag("auth_password").assert(isPassword())
        assertPasswordRendering("A careful long passphrase", visible = false)
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        assertPasswordRendering("A careful long passphrase", visible = true)
        compose.onNodeWithTag("auth_password").assertTextContains("A careful long passphrase")
        compose.onNodeWithContentDescription("Hide password").performScrollTo().performClick()
        compose.onNodeWithTag("auth_password").assert(isPassword())
        assertPasswordRendering("A careful long passphrase", visible = false)
        click("Sign in")
        compose.runOnIdle { assertEquals("worker@example.com" to "A careful long passphrase", credentials) }
    }

    @Test
    fun signUpRejectsShortPasswordsAndInvalidEmailBeforeRequestingAccount() {
        var credentials: Pair<String, String>? = null
        var configured by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var loginRequests = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                SignUpScreen(
                    onCreated = { email, password -> credentials = email to password },
                    onLogin = { loginRequests++ }, configured = configured, busy = busy
                )
            }
        }
        replace("auth_password", "abcdefghijk")
        compose.onNodeWithTag("auth_password").assert(hasError())
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        replace("auth_password", "abcdefghijkl")
        replace("auth_email", "invalid")
        click("Create account")
        compose.onNodeWithTag("auth_email").assert(hasError())
        compose.runOnIdle { assertEquals(null, credentials) }
        replace("auth_email", " new-worker@example.com ")
        compose.runOnIdle { configured = false }
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle { assertEquals(null, credentials); configured = true; busy = true }
        compose.onNode(hasText("Creating account…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle { assertEquals(null, credentials); assertEquals(0, loginRequests); busy = false }
        click("Create account")
        compose.runOnIdle { assertEquals("new-worker@example.com" to "abcdefghijkl", credentials) }
    }

    @Test
    fun cloudAvailabilityAndBusyStateGuardLogin() {
        var configured by mutableStateOf(false)
        var busy by mutableStateOf(false)
        val credentials = mutableListOf<Pair<String, String>>()
        var signUpRequests = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { email, password -> credentials += email to password },
                    onSignUp = { signUpRequests++ }, onReset = {},
                    configured = configured, busy = busy, message = null
                )
            }
        }
        replace("auth_email", "worker@example.com")
        replace("auth_password", "A careful long passphrase")
        compose.onNode(hasText("Sign in") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle {
            assertTrue(credentials.isEmpty())
            configured = true
            busy = true
        }
        compose.onNode(hasText("Please wait…") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Create account") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("auth_password").performImeAction()
        compose.runOnIdle {
            assertTrue(credentials.isEmpty())
            assertEquals(0, signUpRequests)
            busy = false
        }
        click("Sign in")
        compose.runOnIdle {
            assertEquals(listOf("worker@example.com" to "A careful long passphrase"), credentials)
        }
    }

    @Test
    fun passwordResetRequiresValidEmailAndAvailableCloudAndCancelDoesNotSend() {
        var configured by mutableStateOf(true)
        var busy by mutableStateOf(false)
        val resetRequests = mutableListOf<String>()
        compose.setEnglishContent {
            HeatShieldTheme {
                LoginScreen(
                    onLogin = { _, _ -> }, onSignUp = {}, onReset = { resetRequests += it },
                    configured = configured, busy = busy, message = null
                )
            }
        }
        click("Forgot password?")
        val send = compose.onNodeWithText("Send reset email")
        val dialogEmail = compose.onNode(hasTestTag("auth_email") and hasAnyAncestor(isDialog()))
        send.assertIsNotEnabled()
        replace(dialogEmail, "invalid")
        dialogEmail.assert(hasError())
        send.assertIsNotEnabled()
        replace(dialogEmail, " worker@example.com ")
        send.assertIsEnabled()
        compose.runOnIdle { busy = true }
        send.assertIsNotEnabled()
        compose.runOnIdle { busy = false; configured = false }
        send.assertIsNotEnabled()
        compose.runOnIdle { configured = true }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Reset password").assertDoesNotExist()
        compose.runOnIdle { assertTrue(resetRequests.isEmpty()) }
        click("Forgot password?")
        compose.onNodeWithText("Send reset email").assertIsEnabled().performClick()
        compose.onNodeWithText("Reset password").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("worker@example.com"), resetRequests) }
    }

    @Test
    fun missingForecastCannotBeSavedAndReturningToInputsRetainsContext() {
        var comparing by mutableStateOf(true)
        var accepted = 0
        val context = WorkContext(work = "Ride-on mowing", effort = "Moderate effort", clothing = "Heavy protective clothing")
        compose.setEnglishContent {
            HeatShieldTheme {
                ShiftPlanScreen(
                    acceptedWindow = "", initialContext = context, comparing = comparing,
                    onStepChange = { comparing = it }, onReturnToToday = {},
                    forecast = null, refreshing = false, sites = WorkSite.defaults.map { it.name },
                    onRefresh = {}, onAccept = { _, _ -> accepted++ }
                )
            }
        }
        compose.onNodeWithText("No comparison available").assertExists()
        compose.onNodeWithText("Save this window").assertDoesNotExist()
        click("Change your day")
        compose.onNodeWithText("Step 1 of 2 · Your day").assertExists()
        compose.onNode(hasText("Work type") and hasText("Ride-on mowing · moderate work")).assertExists()
        compose.onNodeWithText("Effort").assertDoesNotExist()
        compose.onNode(hasText("Protective clothing") and hasText("Heavy protective clothing")).assertExists()
        // Pickers carry no asterisk, so the form has no legend either.
        compose.onNodeWithText("* required").assertDoesNotExist()
        choose("Work type", "Pruning & hedging · moderate work")
        click("Compare work windows")
        compose.onNodeWithText("No comparison available").assertExists()
        compose.onNodeWithText("Save this window").assertDoesNotExist()
        click("Change your day")
        compose.onNode(hasText("Work type") and hasText("Pruning & hedging · moderate work")).assertExists()
        compose.runOnIdle { assertEquals(0, accepted) }
    }

    @Test
    fun choosingAWindowSavesEditedContextAndUnavailableForecastWithdrawsTheAction() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        val initial = WorkContext(dayMillis = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        var snapshot by mutableStateOf<ForecastSnapshot?>(ForecastSnapshot(
            site = "Carlton Gardens", fetchedAt = System.currentTimeMillis(),
            hours = (7..10).map { hour -> ForecastHour(tomorrow.atTime(hour, 0).toString(), 20.0 + hour, 50.0, 2.0) }
        ))
        var comparing by mutableStateOf(false)
        val refreshRequests = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, WorkContext>>()
        var returns = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                ShiftPlanScreen(
                    acceptedWindow = "", initialContext = initial, forecast = snapshot,
                    comparing = comparing, onStepChange = { comparing = it },
                    onRefresh = { refreshRequests += it }, onReturnToToday = { returns++ },
                    onAccept = { window, context -> saved += window to context }
                )
            }
        }
        choose("Park", "Carlton Gardens")
        choose("Work type", "Pruning & hedging · moderate work")
        choose("Protective clothing", "Heavy protective clothing")
        click("Compare work windows")
        reach(hasText("Pruning & hedging · moderate work · Heavy protective clothing")).assertExists()
        val early = windowText(7, 9)
        val late = windowText(8, 10)
        reach(hasText(early) and hasClickAction()).assertIsSelected()
        // Whole degrees, humidity and a UV word, as on Today: 7–9 am peaks at 29°, UV 2 is Low.
        reach(hasText("Up to 29° · 50% humidity · UV Low") and hasClickAction()).assertIsSelected()
        click(late)
        reach(hasText(late) and hasClickAction()).assertIsSelected()
        reach(hasText(early) and hasClickAction()).assertIsNotSelected()
        click("Save this window")
        compose.runOnIdle {
            assertEquals(listOf("Carlton Gardens"), refreshRequests)
            assertEquals(listOf("08:00–10:00" to initial.copy(
                site = "Carlton Gardens", work = "Pruning & hedging", effort = "Moderate effort",
                clothing = "Heavy protective clothing"
            )), saved)
            snapshot = null
        }
        compose.onNodeWithText("Save this window").assertDoesNotExist()
        click("Retry forecast")
        click("Return to Today")
        compose.runOnIdle {
            assertEquals(listOf("Carlton Gardens", "Carlton Gardens"), refreshRequests)
            assertEquals(1, saved.size)
            assertEquals(1, returns)
        }
    }

    // A refresh that lands between the screen's 15 s ticks carries a fetch time after the last tick. The windows stay
    // on screen, and so does the one the gardener picked; nothing falls back to "No comparison available".
    @Test
    fun aForecastFetchedAfterTheLastTickKeepsTheWindowsAndTheChoice() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        var snapshot by mutableStateOf(planForecast(tomorrow))
        compose.setEnglishContent {
            HeatShieldTheme {
                ShiftPlanScreen(acceptedWindow = "", initialContext = planContext(tomorrow), forecast = snapshot,
                    comparing = true, onStepChange = {}, onAccept = { _, _ -> })
            }
        }
        val early = windowText(7, 9)
        val late = windowText(8, 10)
        click(late)
        reach(hasText(late) and hasClickAction()).assertIsSelected()
        compose.runOnIdle { snapshot = snapshot.copy(fetchedAt = System.currentTimeMillis()) }
        compose.onNodeWithText("No comparison available").assertDoesNotExist()
        reach(hasText(late) and hasClickAction()).assertIsDisplayed().assertIsSelected()
        reach(hasText(early) and hasClickAction()).assertIsDisplayed().assertIsNotSelected()
    }

    // A fetch time is never trusted beyond the real now. After the phone clock moves back, a cache stamped an hour ahead
    // offers no window and no Save, as savePlan would refuse the same window.
    @Test
    fun aForecastStampedInTheFutureOffersNoWindowAndNoSave() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        compose.setEnglishContent {
            HeatShieldTheme {
                ShiftPlanScreen(acceptedWindow = "", initialContext = planContext(tomorrow),
                    forecast = planForecast(tomorrow).copy(fetchedAt = System.currentTimeMillis() + 3_600_000L),
                    comparing = true, onStepChange = {}, onAccept = { _, _ -> })
            }
        }
        compose.onNodeWithText("No comparison available").assertIsDisplayed()
        compose.onNodeWithText("Save this window").assertDoesNotExist()
        compose.onAllNodes(hasText(windowText(7, 9))).assertCountEquals(0)
    }

    // Spec §7, 4.5:1 in dark theme too: each window card sits on a dark surface that the theme's text colours read on.
    // Sampled inside the card's right padding, where nothing but the card's own fill is drawn.
    @Test
    fun windowCardsStayReadableInDarkTheme() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        compose.setEnglishContent {
            HeatShieldTheme(darkTheme = true) {
                ShiftPlanScreen(acceptedWindow = "", initialContext = planContext(tomorrow), forecast = planForecast(tomorrow),
                    comparing = true, onStepChange = {}, onAccept = { _, _ -> })
            }
        }
        val inset = with(compose.density) { 8.dp.roundToPx() }
        listOf(windowText(7, 9), windowText(8, 10)).forEach { window ->
            val pixels = reach(hasText(window) and hasClickAction()).assertIsDisplayed().captureToImage().toPixelMap()
            val fill = pixels[pixels.width - inset, pixels.height / 2]
            listOf("ink" to Palette.DARK_INK, "muted" to Palette.DARK_MUTED).forEach { (name, token) ->
                val ratio = contrast(Color(token), fill)
                assertTrue("$window: dark $name text on $fill is $ratio:1", ratio >= 4.5)
            }
        }
    }

    // Blue means "you can tap this" and red means first aid, feeling unwell or deleting. Plan's air chart is data, so it
    // is drawn in ink; the usual-hours check is an ordinary notice, so it is ink too. Neither shows a pixel of either colour.
    @Test
    fun theAirChartAndTheUsualHoursCheckUseNeitherActionBlueNorEmergencyRed() {
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        var hoursCheck by mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme(darkTheme = false) {
                if (hoursCheck) PageColumn { UsualHoursFields(14 * 60, 14 * 60 + 30, {}, {}) }
                else ShiftPlanScreen(acceptedWindow = "", initialContext = planContext(tomorrow), forecast = planForecast(tomorrow),
                    comparing = true, onStepChange = {}, onAccept = { _, _ -> })
            }
        }
        fun pixelsNear(node: SemanticsNodeInteraction, token: Long): Int {
            val target = Color(token)
            val pixels = node.captureToImage().toPixelMap()
            var count = 0
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                val p = pixels[x, y]
                if (kotlin.math.abs(p.red - target.red) < 0.06f && kotlin.math.abs(p.green - target.green) < 0.06f &&
                    kotlin.math.abs(p.blue - target.blue) < 0.06f) count++
            }
            return count
        }
        val chart = reach(hasContentDescription("degrees", substring = true))
        assertEquals("Action blue in the air chart", 0, pixelsNear(chart, Palette.LIGHT_ACTION))
        assertTrue("The air chart draws its line in ink", pixelsNear(chart, Palette.LIGHT_INK) > 0)
        compose.runOnIdle { hoursCheck = true }
        val check = reach(hasText("Finish must be at least an hour after start"))
        assertEquals("Emergency red in the usual-hours check", 0, pixelsNear(check, Palette.LIGHT_EMERGENCY))
        assertTrue("The usual-hours check reads in ink", pixelsNear(check, Palette.LIGHT_INK) > 0)
        compose.onNodeWithTag("hours_finish").assert(hasError())
    }

    @Test
    fun logListsMeasuredShiftsOpensDetailFixTimesAndDeletesAfterConfirmation() {
        val day = LocalDate.of(2026, 9, 29)
        fun ms(time: java.time.LocalDateTime) = heatshield.domain.ShiftRules.epochMillis(time)
        val tracked = WorkSession(4, "worker-test", "Push mowing", "Royal Park", "29 Sep 2026", 420, 60, ShiftStatus.TO_RAISE,
            startedAt = ms(day.atTime(7, 0)), endedAt = ms(day.atTime(15, 0)), source = ShiftSource.TRACKED, peakHeat = 36.0,
            peakLevel = heatshield.domain.HeatLevel.VERY_HOT.name, longestStretchMinutes = 150, autoEnded = true)
        val events = listOf(
            ShiftEvent(id = 1, shiftId = 4, type = ShiftEventType.SMOKO, startedAt = ms(day.atTime(9, 30)), endedAt = ms(day.atTime(9, 50))),
            ShiftEvent(id = 2, shiftId = 4, type = ShiftEventType.BREAK, startedAt = ms(day.atTime(10, 0)), endedAt = ms(day.atTime(10, 15)), reason = BreakReason.NO_SHADE),
            ShiftEvent(id = 3, shiftId = 4, type = ShiftEventType.LUNCH, startedAt = ms(day.atTime(12, 0)), endedAt = ms(day.atTime(12, 30)))
        )
        val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        fun at(hour: Int, minute: Int = 0) = Formats.time(LocalTime.of(hour, minute), use24Hour)
        var sessions by mutableStateOf(records + tracked)
        var route by mutableStateOf("records")
        var selectedId by mutableStateOf<Int?>(null)
        val fixed = mutableListOf<ShiftTimesEdit>()
        compose.setEnglishContent {
            HeatShieldTheme {
                Surface(Modifier.fillMaxSize()) {
                    val selected = sessions.find { it.id == selectedId }
                    when (route) {
                        "records" -> RecordsScreen(sessions, onDetail = { selectedId = it; route = "detail" }, onAdd = { route = "add" }, onSearch = {})
                        "detail" -> selected?.let { session ->
                            SessionDetailScreen(session, if (session.id == 4) events else emptyList(), onFixTimes = { route = "fix" },
                                onDelete = { sessions = sessions.filterNot { it.id == session.id }; route = "records" })
                        }
                        "fix" -> selected?.let { FixTimesScreen(it, events, 570, 720, ms(day.atTime(16, 0)), saving = false, heatRecalculated = false) { edit -> fixed += edit; route = "detail" } }
                        "add" -> AddPastShiftScreen(WorkSite.defaults.map { it.name }, "Birrarung Marr", ms(day.atTime(16, 0)), saving = false) { route = "records" }
                    }
                }
            }
        }
        reach(hasText("4 shifts")).assertExists()
        reach(hasText("${at(7)} – ${at(15)} · 7 h worked · 1 h breaks")).assertExists()
        reach(hasText("Ended automatically")).assertExists()
        compose.onAllNodes(hasText("Added by hand")).assertCountEquals(3)   // the legacy typed rows
        compose.onAllNodes(hasText("To raise")).assertCountEquals(3)
        compose.onNodeWithText("Nothing to raise").assertDoesNotExist()
        compose.onAllNodes(hasText("none") or hasText("to_raise") or hasText("tracked")).assertCountEquals(0)
        click("${at(7)} – ${at(15)} · 7 h worked · 1 h breaks")
        reach(hasText("Peak Very hot 36°")).assertExists()
        reach(hasText("Something to raise with your team leader.")).assertExists()
        reach(hasText("Break ${at(10)} – ${at(10, 15)} · no shade here")).assertExists()
        reach(hasTestTag("detail_fix")).assertTextEquals("Fix times").performClick()
        reach(hasText("Heat not recalculated: no forecast is saved for this shift's park and day.")).assertExists()
        reach(hasTestTag("fix_save")).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, fixed.single().breaks.size); assertEquals("detail", route) }   // smoko, break, lunch all have events; nothing pending
        click("Delete shift")
        compose.onNodeWithText("Delete this shift?").assertExists()
        compose.onNodeWithText("Keep shift").performClick()
        compose.runOnIdle { assertEquals(4, sessions.size) }
        click("Delete shift")
        compose.onNode(hasText("Delete shift") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        reach(hasText("3 shifts")).assertExists()
        click("Add a past shift")
        reach(hasText("* required")).assertExists()
        reach(hasText("Date*")).assertExists(); reach(hasText("Started*")).assertExists(); reach(hasText("Finished*")).assertExists()
        compose.onAllNodes(hasSetTextAction() and hasText("min", substring = true)).assertCountEquals(0)   // no typed minutes anywhere
        reach(hasTestTag("past_save")).assertIsNotEnabled()
    }

    @Test
    fun searchCombinesFiltersResetsAndSortsRealRecords() {
        var openedId: Int? = null
        // The longest record must differ from the newest or a broken sort could pass.
        val searchRecords = records.map { if (it.id == 3) it.copy(outdoorMinutes = 125) else it }
        compose.setEnglishContent {
            HeatShieldTheme { SearchScreen(searchRecords) { openedId = it } }
        }
        compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction()).onFirst()
            .assert(hasText("1 h 35 min outdoors · 15 min breaks"))
        replace("search_query", " BIRRARUNG ")
        reach(hasText("2 results")).assertExists()
        click("Filters")
        choose("Follow-up", "To raise")
        reach(hasText("1 result")).assertExists()
        choose("Work type", "Mowing")
        reach(hasText("No matching shifts")).assertExists()
        choose("Work type", "Planting beds")
        click("Any date")
        compose.onNodeWithText("Use date").performClick()
        reach(hasText("1 result")).assertExists()
        click("Reset")
        reach(hasText("3 results")).assertExists()
        replace("search_query", "missing worksite")
        reach(hasText("No matching shifts")).assertExists()
        click("Reset")
        click("Newest first")
        compose.onNodeWithText("Longest outdoors").performClick()
        val first = compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction()).onFirst()
        first.assert(hasText("2 h 5 min outdoors · 20 min breaks"))
        first.performClick()
        compose.runOnIdle { assertEquals(3, openedId) }
    }

    // F5 over measured shifts: a word from a shift's note finds it, and its card reads in worked minutes.
    @Test
    fun searchFindsAMeasuredShiftByAWordInItsNote() {
        val day = LocalDate.of(2026, 9, 29)
        fun ms(time: java.time.LocalDateTime) = heatshield.domain.ShiftRules.epochMillis(time)
        val measured = WorkSession(4, "worker-test", "Push mowing", "Royal Park", "29 Sep 2026", 420, 60, ShiftStatus.NONE,
            startedAt = ms(day.atTime(7, 0)), endedAt = ms(day.atTime(15, 0)), source = ShiftSource.TRACKED, note = "Shade sail torn")
        var openedId: Int? = null
        compose.setEnglishContent {
            HeatShieldTheme { SearchScreen(records + measured) { openedId = it } }
        }
        compose.onNode(hasText("Work type, park or note") and hasSetTextAction()).assertExists()
        replace("search_query", "sail")
        reach(hasText("1 result")).assertExists()
        val card = compose.onNode(hasText(" worked · ", substring = true) and hasClickAction())
        card.assertExists().performClick()
        compose.runOnIdle { assertEquals(4, openedId) }
        compose.onAllNodes(hasText("min outdoors", substring = true) and hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun trendsKeepWorkAndBreakTotalsSeparateAndWeeksBeginOnMonday() {
        // A preview shift changes no total: Trends counts measured and typed shifts only, and says so.
        compose.setEnglishContent {
            HeatShieldTheme { TrendsScreen(records + records[0].copy(id = 9, source = ShiftSource.PREVIEW, outdoorMinutes = 999)) }
        }
        reach(hasText("195", substring = false)).assertExists()
        reach(hasText("45", substring = false)).assertExists()
        click("By week")
        click("Data values")
        reach(hasText("Week of ${Formats.fullDate(LocalDate.of(2026, 9, 21))}")).assertExists()
        compose.onAllNodesWithText("Outdoor work 100 min · Logged breaks 30 min")
            .onLast().performScrollTo().assertExists()
        reach(hasText("Week of ${Formats.fullDate(LocalDate.of(2026, 9, 28))}")).assertExists()
        choose("Work type", "Mowing")
        reach(hasText("40", substring = false)).assertExists()
        reach(hasText("10", substring = false)).assertExists()
        choose("Work type", "Pruning & hedging")
        reach(hasText("No shifts in this view")).assertExists()
        choose("Work type", "Planting beds")
        reach(hasText("155", substring = false)).assertExists()
        reach(hasText("35", substring = false)).assertExists()
        click("How totals work")
        reach(hasText("Preview shifts aren't counted.")).assertExists()
    }

    @Test
    fun loginHasNoDeviceModeAndResetSuccessReadsAsNeutral() {
        val sent = "If the account is eligible, password reset instructions will arrive by email."
        var message by mutableStateOf<UiMessage?>(UiMessage(sent, isError = false))
        compose.setEnglishContent {
            HeatShieldTheme {
                LoginScreen(onLogin = { _, _ -> }, onSignUp = {}, onReset = {}, configured = true,
                    busy = false, message = message)
            }
        }
        compose.onNodeWithText("Continue on this device").assertDoesNotExist()
        compose.onNodeWithText("device mode", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag("auth_message").performScrollTo().assertTextEquals(sent)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.runOnIdle { message = UiMessage("Incorrect email or password.", isError = true) }
        compose.onNodeWithTag("auth_message").performScrollTo().assert(hasError())
    }

    @Test
    fun welcomeStartsWithoutAnAccountAndKeepsSignInOneTapAway() {
        val taps = mutableListOf<String>()
        compose.setEnglishContent {
            HeatShieldTheme {
                WelcomeScreen(onGetStarted = { taps += "start" }, onSignIn = { taps += "sign-in" },
                    onCreateAccount = { taps += "create" })
            }
        }
        reach(hasText("Know your park's heat before you clock on, and get a nudge when a break is due.")).assertExists()
        reach(hasText("No account needed. Your shifts stay on this phone.")).assertExists()
        click("Get started")
        click("Sign in")
        click("Create account")
        compose.runOnIdle { assertEquals(listOf("start", "sign-in", "create"), taps) }
    }

    @Test
    fun setupBlocksAFinishWithinAnHourOfStartThenSendsTheChoices() {
        var initial by mutableStateOf(UserPreferences(ownerId = "worker-test", shiftStartMinute = 14 * 60,
            shiftEndMinute = 14 * 60 + 30))
        val finished = mutableListOf<List<Any>>()
        compose.setEnglishContent {
            HeatShieldTheme {
                key(initial) {
                    SetupScreen(WorkSite.defaults.map { it.name }, initial, busy = false) { site, task, start, end, smoko, lunch, acclimatising ->
                        finished += listOf(site, task, start, end, smoko, lunch, acclimatising)
                    }
                }
            }
        }
        reach(hasText("Finish must be at least an hour after start")).assertExists()
        compose.onNodeWithTag("hours_finish").assert(hasError())
        reach(hasTestTag("setup_done")).assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(finished.isEmpty())
            initial = UserPreferences(ownerId = "worker-test")
        }
        compose.onNodeWithText("Finish must be at least an hour after start").assertDoesNotExist()
        compose.onNodeWithTag("hours_finish").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        choose("Park", "Royal Park")
        choose("Usual work", "Push mowing · heavy work")
        reach(hasText("New to outdoor work, or back from leave this week")).performClick()
        reach(hasTestTag("setup_done")).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(listOf<Any>("Royal Park", "Push mowing", 420, 900, 570, 720, true)), finished)
        }
    }

    // The smoko time the gardener picks is the one setup saves (typed fields: the dialog at double font).
    @Test
    fun setupSendsTheChosenSmokoTime() {
        val finished = mutableListOf<List<Any>>()
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    SetupScreen(WorkSite.defaults.map { it.name }, UserPreferences(ownerId = "worker-test"), busy = false) { site, task, start, end, smoko, lunch, acclimatising ->
                        finished += listOf(site, task, start, end, smoko, lunch, acclimatising)
                    }
                }
            }
        }
        val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        reach(hasTestTag("hours_smoko")).performClick()
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields[0].performTextReplacement("10")
        if (!use24Hour) compose.onNode((hasText("AM") or hasText("a.m.")) and hasAnyAncestor(isDialog())).performClick()
        fields[1].performTextReplacement("00")
        compose.onNode(hasText("Set time") and hasClickAction()).performClick()
        reach(hasTestTag("hours_smoko")).assertTextContains(Formats.time(LocalTime.of(10, 0), use24Hour), substring = true)
        reach(hasTestTag("setup_done")).assertIsEnabled().performClick()
        compose.runOnIdle {
            val sent = finished.single()
            assertEquals(600, sent[4]); assertEquals(720, sent[5])
        }
    }

    @Test
    fun setupShowsSmokoAndLunchPrefilledAndProfileBreakRuleChangesItsSource() {
        var rule by mutableStateOf(BreakRule.HEATSHIELD)
        var showProfileRule by mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme {
                if (showProfileRule) PageColumn { BreakRuleSection(rule, { rule = it }) }
                else SetupScreen(WorkSite.defaults.map { it.name }, UserPreferences(ownerId = "worker-test"), busy = false) { _, _, _, _, _, _, _ -> }
            }
        }
        val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        reach(hasTestTag("hours_smoko")).assertTextContains(Formats.time(LocalTime.of(9, 30), use24Hour), substring = true)
        reach(hasTestTag("hours_lunch")).assertTextContains(Formats.time(LocalTime.of(12, 0), use24Hour), substring = true)
        reach(hasText("Smoko and lunch are proposed at these times. Only a break you confirm counts.")).assertExists()
        compose.onNodeWithText("Smoko*").assertDoesNotExist()
        compose.runOnIdle { showProfileRule = true }
        reach(hasText("HeatShield suggestion") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag(SOURCE_CHIP_TAG).assertContentDescriptionEquals("Source: HeatShield rule")
        reach(hasText("Union table (VTHC), adapted by HeatShield") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(BreakRule.UNION_VTHC, rule) }
        reach(hasText("Union table (VTHC), adapted by HeatShield") and hasClickAction()).assertIsSelected()
        // Both options are HeatShield's decisions: the chip never names VTHC, the text credits it (spec §5.10).
        compose.onNodeWithTag(SOURCE_CHIP_TAG).assertContentDescriptionEquals("Source: HeatShield rule")
        compose.onAllNodesWithContentDescription("Source: Union starting point (VTHC)").assertCountEquals(0)
        reach(hasText("A HeatShield adaptation, not the union's own rule.", substring = true)).assertExists()
    }

    @Test
    fun setupStaysUsableAtDoubleFontSize() {
        var done = 0
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    SetupScreen(WorkSite.defaults.map { it.name }, UserPreferences(ownerId = "worker-test"), busy = false) { _, _, _, _, _, _, _ -> done++ }
                }
            }
        }
        reach(hasText("Usual work")).assertIsDisplayed()
        reach(hasTestTag("setup_done")).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, done) }
    }

    // A brand-new owner has no earlier settings or log: setup says nothing about them and starts from the default job.
    // An owner upgraded from the previous version is told their settings were kept, and the job matches their effort.
    @Test
    fun onlyAnUpgradedOwnerIsToldTheirSettingsWereKept() {
        var initial by mutableStateOf(UserPreferences(ownerId = "worker-test"))
        var upgrading by mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme {
                key(initial, upgrading) {
                    SetupScreen(WorkSite.defaults.map { it.name }, initial, busy = false, upgrading = upgrading) { _, _, _, _, _, _, _ -> }
                }
            }
        }
        compose.onNodeWithText("suggested job", substring = true).assertDoesNotExist()
        reach(hasText("Usual work") and hasText("Digging & planting · heavy work")).assertExists()
        compose.runOnIdle {
            initial = UserPreferences(ownerId = "worker-test", effort = "Moderate effort")
            upgrading = true
        }
        reach(hasText("suggested job", substring = true)).assertExists()
        reach(hasText("Usual work") and hasText("Ride-on mowing · moderate work")).assertExists()
    }

    // Review Focus 4: Welcome and the Profile hot-day rule at 200 % font on a 360 dp phone.
    @Test
    fun welcomeAndHotDayRuleAtDoubleFont() {
        var showRule by mutableStateOf(false)
        var trigger by mutableStateOf(35.0)
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    Box(Modifier.width(360.dp)) {
                        if (showRule) PageColumn { HotDayRuleSection(trigger, { trigger = it }) }
                        else WelcomeScreen(onGetStarted = {}, onSignIn = {}, onCreateAccount = {})
                    }
                }
            }
        }
        reach(hasText("Know your park's heat before you clock on, and get a nudge when a break is due.")).assertIsDisplayed()
        reach(hasText("Get started") and hasClickAction()).assertIsDisplayed()
        reach(hasText("Create account") and hasClickAction()).assertIsDisplayed()
        compose.runOnIdle { showRule = true }
        reach(hasText("Custom") and hasClickAction()).assertIsDisplayed().performClick()
        reach(hasTestTag("rule_custom")).assertIsDisplayed()
        reach(hasText("Air temperature in the shade. Ask your team leader which number your crew uses.")).assertIsDisplayed()
    }

    // Review Focus 5 through the real input path: start button, time dialog, Set time.
    @Test
    fun setupTimeDialogBlocksAFinishWithinAnHour() {
        val is24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    SetupScreen(WorkSite.defaults.map { it.name }, UserPreferences(ownerId = "worker-test"), busy = false) { _, _, _, _, _, _, _ -> }
                }
            }
        }
        reach(hasTestTag("hours_start")).performClick()
        // Above 130 % font the dialog offers typed hour and minute fields (TimeInput), not the dial.
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields.assertCountEquals(2)
        fields[0].performTextReplacement(if (is24Hour) "14" else "2")
        // Dialogs follow the device language: Material 3 labels the toggle "PM" in English (United States) and
        // "p.m." in English (Australia), the two languages Global Constraints allow on the test AVD.
        if (!is24Hour) compose.onNode((hasText("PM") or hasText("p.m.")) and hasAnyAncestor(isDialog())).performClick()
        fields[1].performTextReplacement("30")
        click("Set time", scroll = false)
        // Start 2:30 pm against the default 3 pm finish.
        reach(hasText("Finish must be at least an hour after start")).assertExists()
        compose.onNodeWithTag("hours_finish").assert(hasError())
        reach(hasTestTag("setup_done")).assertIsNotEnabled()
    }

    // Profile keeps a draft of the usual hours, so the gardener can pass through a pair that is not valid yet. A valid
    // 8:00 start is saved and shown at once; when that save fails the model puts back the stored 7:00 start, and the
    // field follows it rather than keeping the hour that was never saved. A 7:30 finish (under an hour) stays as a draft.
    @Test
    fun profileHoursFollowTheStoredHoursWhenASaveFails() {
        val is24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        var saved by mutableStateOf(420 to 900)
        val attempts = mutableListOf<Pair<Int, Int>>()
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    // As updatePreferences does: the screen shows the new hours at once, before the write.
                    PageColumn { ProfileHoursFields("worker-test", saved.first, saved.second) { start, end -> attempts += start to end; saved = start to end } }
                }
            }
        }
        fun time(hour: Int, minute: Int) = Formats.time(LocalTime.of(hour, minute), is24Hour)
        pickHoursTime("hours_start", 8, "00", is24Hour)
        reach(hasTestTag("hours_start")).assertTextContains(time(8, 0), substring = true)
        // The write fails: the model restores the stored row, 7:00–15:00.
        compose.runOnIdle { assertEquals(listOf(480 to 900), attempts); saved = 420 to 900 }
        reach(hasTestTag("hours_start")).assertTextContains(time(7, 0), substring = true)
        // A finish under an hour after the start is a draft: shown with its error, never saved, and kept on screen.
        pickHoursTime("hours_finish", 7, "30", is24Hour)
        reach(hasTestTag("hours_finish")).assertTextContains(time(7, 30), substring = true).assert(hasError())
        compose.runOnIdle { assertEquals(listOf(480 to 900), attempts) }
    }

    // The quick on-screen update and the restore after a failed write can both land before the next frame (two short
    // Room round trips on the main thread). The field still follows the stored row, so the rejected 8:00 never stays. A
    // pair that is not valid yet stays a draft; the next valid pick clears it, and the fields follow the stored row again.
    @Test
    fun profileHoursFollowAStoredRowRestoredWithinTheSameFrame() {
        val is24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        var saved by mutableStateOf(420 to 900)
        val attempts = mutableListOf<Pair<Int, Int>>()
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    PageColumn {
                        ProfileHoursFields("worker-test", saved.first, saved.second) { start, end ->
                            attempts += start to end
                            val stored = saved
                            saved = start to end // shown at once, as updatePreferences does
                            saved = stored // the write failed: the stored row is back before any frame ran
                        }
                    }
                }
            }
        }
        fun time(hour: Int, minute: Int) = Formats.time(LocalTime.of(hour, minute), is24Hour)
        pickHoursTime("hours_start", 8, "00", is24Hour)
        reach(hasTestTag("hours_start")).assertTextContains(time(7, 0), substring = true)
        compose.runOnIdle { assertEquals(listOf(480 to 900), attempts) }
        pickHoursTime("hours_finish", 7, "30", is24Hour)
        reach(hasTestTag("hours_finish")).assertTextContains(time(7, 30), substring = true).assert(hasError())
        compose.runOnIdle { assertEquals(listOf(480 to 900), attempts) }
        // 16:00 makes the pair valid: it is saved, the draft is gone, and the restored 15:00 finish shows with no error.
        pickHoursTime("hours_finish", 16, "00", is24Hour)
        compose.runOnIdle { assertEquals(listOf(480 to 900, 420 to 960), attempts) }
        reach(hasTestTag("hours_finish")).assertTextContains(time(15, 0), substring = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        reach(hasTestTag("hours_start")).assertTextContains(time(7, 0), substring = true)
    }

    /**
     * Opens the usual-hours field tagged [tag] and sets [hour] (0–23) and [minute] in its typed time dialog, which the
     * tests' 2.0 font scale selects. In 12-hour time it types the 12-hour hour and picks AM or PM; Material 3 labels the
     * toggle "AM"/"PM" in English (United States) and "a.m."/"p.m." in English (Australia).
     * Example: 16, "00" on a 12-hour phone types 4 and 00 and picks PM.
     */
    private fun pickHoursTime(tag: String, hour: Int, minute: String, is24Hour: Boolean) {
        reach(hasTestTag(tag)).performClick()
        val fields = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
        fields[0].performTextReplacement((if (is24Hour) hour else (hour + 11) % 12 + 1).toString())
        val toggle = if (hour < 12) hasText("AM") or hasText("a.m.") else hasText("PM") or hasText("p.m.")
        if (!is24Hour) compose.onNode(toggle and hasAnyAncestor(isDialog())).performClick()
        fields[1].performTextReplacement(minute)
        click("Set time", scroll = false)
    }

    @Test
    fun customHotDayRuleRejectsBadInputAndKeepsThePreviousRule() {
        var trigger by mutableStateOf(35.0)
        compose.setEnglishContent { HeatShieldTheme { PageColumn { HotDayRuleSection(trigger, { trigger = it }) } } }
        // Spec §5.7: the default is an example threshold to confirm, never a claim about local practice. A preset
        // carries [Workplace rule example]; a typed rule is the user's own, so it carries [Your setting].
        // SourceChip speaks one combined description; its child Text is intentionally cleared from semantics.
        reach(hasText("35° · example threshold, confirm with your team leader")).assertIsSelected()
        reach(hasContentDescription("Source: Workplace rule example")).assertExists()
        click("Custom")
        // Choosing the editor alone does not replace the saved 35-degree rule.
        reach(hasContentDescription("Source: Workplace rule example")).assertExists()
        compose.onNodeWithContentDescription("Source: Your setting").assertDoesNotExist()
        // "36.5" fails too: a crew rule is a whole number of degrees (HeatRules.isValidTrigger).
        listOf("", "abc", "10", "70", "36.5").forEach { bad ->
            replace("rule_custom", bad)
            reach(hasText("Enter a whole number from 25 to 50")).assertExists()
            compose.onNodeWithTag("rule_custom").assert(hasError())
            compose.runOnIdle { assertEquals("'$bad' must keep the 35° rule", 35.0, trigger, 0.0) }
            reach(hasContentDescription("Source: Workplace rule example")).assertExists()
            compose.onNodeWithContentDescription("Source: Your setting").assertDoesNotExist()
        }
        replace("rule_custom", "36")
        compose.onNodeWithText("Enter a whole number from 25 to 50").assertDoesNotExist()
        compose.runOnIdle { assertEquals(36.0, trigger, 0.0) }
        reach(hasContentDescription("Source: Your setting")).assertExists()
        compose.onNodeWithContentDescription("Source: Workplace rule example").assertDoesNotExist()
    }

    @Test
    fun choosingThe38RuleChangesTodaysCrewLine() {
        var trigger by mutableStateOf(35.0)
        compose.setEnglishContent {
            HeatShieldTheme {
                Column(Modifier.fillMaxSize()) {
                    HotDayRuleSection(trigger, { trigger = it })
                    Box(Modifier.weight(1f)) {
                        TodayContent(heat = ruleDayHeat(trigger), sites = WorkSite.defaults.map { it.name },
                            refreshing = false, nowMillis = ruleDayClock.millis(),
                            onSelectSite = {}, onRefresh = {}, onOpenPreview = {}, onOpenPlan = {},
                            onOpenFirstAid = {})
                    }
                }
            }
        }
        // The crew rule compares whole air degrees: 36.0 shows as 36°, which reaches a 35° rule (36 >= 35)
        // and stays under a 38° one (36 < 38). Task 8's today_crew_passes / today_crew_under copy.
        reach(hasTestTag("today_line_crew")).assertTextContains("Air forecast to reach 35°", substring = true)
        // The rule section sits outside TodayContent's scrolling column, so there is nothing to scroll.
        click("38°", scroll = false)
        compose.runOnIdle { assertEquals(38.0, trigger, 0.0) }
        reach(hasTestTag("today_line_crew")).assertTextEquals("Air forecast to stay under 38° during your shift")
        compose.onNodeWithText("Air forecast to reach 35°", substring = true).assertDoesNotExist()
    }

    @Test
    fun pullingTodayDownAsksForANewForecast() {
        var refreshes = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                TodayContent(heat = ruleDayHeat(35.0), sites = WorkSite.defaults.map { it.name },
                    refreshing = false, nowMillis = ruleDayClock.millis(),
                    onSelectSite = {}, onRefresh = { refreshes++ }, onOpenPreview = {}, onOpenPlan = {},
                    onOpenFirstAid = {})
            }
        }
        // PageColumn tags its scroll container "page_content"; TodayContent wraps it in PullToRefreshBox.
        compose.onNodeWithTag("page_content").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    /**
     * Fri 9 Jan 2026, 8 am at Birrarung Marr: 26° all day except [hotAirC] (36° by default) at 1 pm and 2 pm, for a
     * 7 am – 3 pm shift.
     */
    private val ruleDay = LocalDate.of(2026, 1, 9)
    private val ruleDayClock = Clock.fixed(ruleDay.atTime(8, 0).atZone(MelbourneZone).toInstant(), MelbourneZone)

    private fun ruleDayHeat(triggerC: Double, hotAirC: Double = 36.0) = ContextReasoner.todayHeat(
        ForecastSnapshot(site = "Birrarung Marr", fetchedAt = ruleDayClock.millis() - 5 * 60_000L,
            hours = (0..23).map { hour ->
                ForecastHour(ruleDay.atTime(hour, 0).toString(), if (hour in 13..14) hotAirC else 26.0, 30.0, 4.0,
                    windSpeed = 3.0, uvClearSky = 5.0)
            }),
        HeatProfile(site = "Birrarung Marr", taskName = "Irrigation & checks", shiftStartMinute = 420,
            shiftEndMinute = 900, triggerC = triggerC, factors = PersonalFactors()),
        ruleDayClock)

    // Off shift there is nothing to time: the break sheet offers to start the shift, not a dead end, and someone
    // unwell reaches first aid before any shift starts (A10).
    @Test
    fun breakSheetWithNoShiftOffersToStartOne() {
        var adds = 0
        var returns = 0
        var firstAid = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                Column { NoShiftForBreak(onStartShift = { adds++ }, onReturn = { returns++ }, onOpenFirstAid = { firstAid++ }) }
            }
        }
        compose.onNodeWithText("No shift running").assertExists()
        compose.onNode(hasText("Start shift") and hasClickAction()).performClick()
        compose.onNode(hasText("Return to Today") and hasClickAction()).performClick()
        compose.onNodeWithTag("break_first_aid").assertTextEquals("Someone unwell? Heat first aid").performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1), listOf(adds, returns, firstAid)) }
    }


    // Spec §10: at 200 % font on a 360 dp phone the check-in's advice stays on screen and every way out of it
    // (start the shift, go back, first aid) is still reachable and tappable.
    @Test
    fun breakCheckInStaysUsableAtDoubleFont() {
        compose.setEnglishContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                HeatShieldTheme {
                    Box(Modifier.width(360.dp)) {
                        PageColumn {
                            BreakHeatAdvice(ruleDayHeat(35.0, hotAirC = 40.0),
                                NextStep.OpenAnswer(OpenAnswerKind.CANT_STOP))
                            NoShiftForBreak(onStartShift = {}, onReturn = {}, onOpenFirstAid = {})
                        }
                    }
                }
            }
        }
        listOf(hasText("Start shift"), hasText("Return to Today"), hasTestTag("break_first_aid")).forEach { control ->
            reach(control and hasClickAction()).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithTag("break_open_answer").assertExists()
        compose.onNodeWithTag("break_extreme").assertExists()
    }

    @Test
    fun disabledActionsDoNotInvokeCallbacksAndEnabledActionsDo() {
        var enabled by mutableStateOf(false)
        var primary = 0
        var secondary = 0
        compose.setEnglishContent {
            HeatShieldTheme {
                Column {
                    PrimaryButton("Save", Modifier.testTag("primary"), enabled) { primary++ }
                    TonalButton("Retry", Modifier.testTag("secondary"), enabled) { secondary++ }
                }
            }
        }
        listOf("primary", "secondary").forEach { compose.onNodeWithTag(it).assertIsNotEnabled().performClick() }
        compose.runOnIdle { assertEquals(0, primary); assertEquals(0, secondary); enabled = true }
        listOf("primary", "secondary").forEach { compose.onNodeWithTag(it).assertIsEnabled().performClick() }
        compose.runOnIdle { assertEquals(1, primary); assertEquals(1, secondary) }
    }

    @Test
    fun sourceAttributionIsSpokenOnceAndBundledFontsLoadOffline() {
        val label = SourceLabel.entries.first()
        compose.setEnglishContent { HeatShieldTheme { SourceChip(label) } }
        compose.onNodeWithTag(SOURCE_CHIP_TAG).assertContentDescriptionEquals("Source: ${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(label))}")
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        assertNotNull(resources.getFont(R.font.atkinson_hyperlegible_next))
        assertNotNull(resources.getFont(R.font.atkinson_hyperlegible_mono))
    }

    private fun assertPasswordRendering(value: String, visible: Boolean) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("auth_password")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Password field did not expose its rendered text layout", layouts.isNotEmpty())
        val expected = if (visible) value else "\u2022".repeat(value.length)
        assertTrue("Password rendering does not match the ${if (visible) "visible" else "masked"} state",
            layouts.any { it.layoutInput.text.text == expected })
    }

    /** A window as the Plan card shows it (`shift_plan_window_time`): the phone's own 12- or 24-hour times around an en dash. */
    private fun windowText(startHour: Int, endHour: Int): String {
        val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
        return "${Formats.time(LocalTime.of(startHour, 0), use24Hour)}–${Formats.time(LocalTime.of(endHour, 0), use24Hour)}"
    }

    /** A fresh forecast for 7 to 10 am on [day] at Birrarung Marr: Plan offers 7–9 am (least heat) and 8–10 am. */
    private fun planForecast(day: LocalDate) = ForecastSnapshot(site = "Birrarung Marr", fetchedAt = System.currentTimeMillis(),
        hours = (7..10).map { hour -> ForecastHour(day.atTime(hour, 0).toString(), 20.0 + hour, 50.0, 2.0) })

    private fun planContext(day: LocalDate) = WorkContext(dayMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

    /** WCAG contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun hasError() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private fun isPassword() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun reach(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitForIdle()
        if (compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun click(text: String, scroll: Boolean = true) {
        val matcher = hasText(text) and hasClickAction()
        (if (scroll) reach(matcher) else compose.onNode(matcher)).performClick()
    }

    private fun replace(tag: String, value: String) = replace(compose.onNodeWithTag(tag).performScrollTo(), value)

    private fun replace(field: SemanticsNodeInteraction, value: String) {
        field.performTextReplacement(value)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun choose(label: String, value: String) {
        reach(hasText(label)).performClick()
        compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
    }

    private val records = listOf(
        WorkSession(1, "worker-test", "Planting beds", "Birrarung Marr", "28 Sep 2026", 95, 15, ShiftStatus.TO_RAISE),
        WorkSession(2, "worker-test", "Mowing", "Birrarung Marr", "27 Sep 2026", 40, 10, ShiftStatus.NONE),
        WorkSession(3, "worker-test", "Planting beds", "Carlton Gardens", "21 Sep 2026", 60, 20, ShiftStatus.TO_RAISE)
    )
}
