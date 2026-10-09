package heatshield

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import heatshield.background.AlertCoordinator
import heatshield.background.ForecastWorker
import heatshield.data.HeatShieldDatabase
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.StartShiftInput
import heatshield.data.WorkSite
import heatshield.data.WorkSession
import heatshield.ui.HeatShieldViewModel
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.work.WorkManager
import com.google.gson.Gson
import heatshield.data.ForecastCache
import heatshield.data.ForecastHour
import heatshield.data.MelbourneZone
import heatshield.data.ShiftPlan
import heatshield.data.UserPreferences
import heatshield.data.Freshness
import androidx.compose.runtime.snapshotFlow
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import heatshield.domain.ShiftRules
import heatshield.domain.WorkTasks
import heatshield.ui.Formats
import heatshield.ui.ShiftUiState
import heatshield.ui.UiMessage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import heatshield.background.AlertScheduler
import heatshield.background.ShiftNotifications
import heatshield.domain.AlarmKind
import kotlinx.coroutines.sync.withLock
import android.app.ActivityOptions
import android.os.Build
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import heatshield.data.BreakReason
import heatshield.domain.BreakDue

/** Real activity/Room journeys; each test restores the local owner and cache state it changes. */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    /**
     * Review Focus 4 and 1: Welcome → setup → Today → Start shift → Start break → recreate → close the activity (Room's
     * anchors moved back, so nothing waits for real minutes) → relaunch recovers the break → End break resets the clock →
     * "Can't stop yet" adds nothing → End shift writes the log and opens the summary → Log → sign out and back in.
     * The relaunch gets a new activity and view model, but this test process and its open database live on: it shows
     * that the shift comes back from Room, not that the app survives Android killing its process.
     */
    @Test
    fun shiftClockSurvivesActivityRelaunchAndWritesTheLog() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking { dao.deletePlan("local"); dao.saveForecast(todayForecast("Birrarung Marr")) }
        deleteLocalPreferences(database)
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var model = modelOf(scenario)
            awaitNode(hasText("Get started") and hasClickAction(), model)
            compose.onNodeWithText("Get started").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Your usual day"), model)
            compose.onNodeWithTag("setup_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            assumeTrue("The dedicated local test profile has no shift running.", model.openShift == null)
            settleAndSeed(database, model, airC = 33.0)   // Hot; the default heavy task makes it Very hot for you (30-minute rhythm)

            // Start shift from Today. The screen is pre-filled from the usual day, so one tap starts the clock.
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            compose.onNode(hasText("Heavy work this shift") and hasClickAction()).performScrollTo().assertIsOn()
            compose.onNodeWithTag("start_shift_confirm").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.openShift != null && !model.busy }
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val shiftId = requireNotNull(model.openShift).shift.id
            compose.onNodeWithTag("shift_status").assertExists()
            compose.onNodeWithTag("shift_start").assertDoesNotExist()
            // A second shift is refused by the DAO transaction, with a sentence from the resources.
            val secondStart = StartShiftInput("Birrarung Marr", "Push mowing", true, false, ShiftRules.localTime(model.now), ShiftRules.localTime(model.now).plusHours(4))
            scenario.onActivity { model.startShift(secondStart) {} }
            compose.waitUntil(10_000) { !model.busy && model.message != null }
            assertEquals(UiMessage(context.getString(R.string.notice_shift_open), isError = true), model.message)
            scenario.onActivity { model.dismissMessage() }

            compose.onNodeWithTag("shift_break_start").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.shiftState is ShiftUiState.OnBreak }
            val breakEvent = (model.shiftState as ShiftUiState.OnBreak).breakEvent
            compose.onNodeWithTag("break_card").assertExists()
            scenario.recreate()
            model = modelOf(scenario)
            compose.waitUntil(10_000) { (model.shiftState as? ShiftUiState.OnBreak)?.breakEvent?.id == breakEvent.id }

            // Close and relaunch: the Activity and ViewModel go; Room keeps the shift. Move the shift back 25 minutes and the
            // break back 11, on both the wall and the monotonic anchors, so the break lies inside the shift (summarise clips
            // breaks to the shift) and ending it resets the clock without waiting. The recovered "now" stays the real time:
            // the elapsed anchor moves by the same 25 minutes as the wall start.
            scenario.close()
            runBlocking {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE work_sessions SET startedAt = startedAt - 1500000, startedElapsed = startedElapsed - 1500000 WHERE id = ?", arrayOf(shiftId))
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE shift_events SET startedAt = startedAt - 660000, startedElapsed = startedElapsed - 660000 WHERE id = ?", arrayOf(breakEvent.id))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            model = modelOf(scenario)
            compose.waitUntil(10_000) { !model.loading && model.shiftState is ShiftUiState.OnBreak }
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val recovered = model.shiftState as ShiftUiState.OnBreak
            assertEquals(shiftId, recovered.shift.id)
            assertTrue("The clock shows the backdated break: ${compose.onNodeWithTag("break_clock")}", Duration.between(
                ShiftRules.instant(recovered.breakEvent.startedAt), recovered.evaluation.now).toMinutes() >= 11)
            compose.onNodeWithTag("shift_break_end").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.shiftState is ShiftUiState.On }
            val afterBreak = model.shiftState as ShiftUiState.On
            assertTrue("An 11-minute break resets the clock: ${afterBreak.evaluation.clock}", afterBreak.evaluation.clock.workedMinutes <= 1)
            assertEquals(ShiftRules.instant(requireNotNull(runBlocking { dao.getEvents(shiftId) }.single().endedAt)), afterBreak.evaluation.clock.since)
            // The relaunch refreshed the park's forecast from the network, which can replace the seeded hours with the real
            // weather (a cool evening has no break due, and the heading below then names no time). Let that refresh settle
            // and seed the 33° hours again, so the break is timed from the seeded heat.
            settleAndSeed(database, model, airC = 33.0)

            // The break sheet: "Can't stop yet" adds nothing and shows on Today as the next step.
            compose.onNodeWithTag("shift_break_sheet").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Break"), model)
            compose.onNode(hasText("Can't stop yet") and hasClickAction()).performScrollTo().performClick()
            // Break-due alerts are off for this fresh owner, so no reminder will post: the choice saves the answer (M3 fix wave).
            compose.onNodeWithText("Remind me", substring = true).assertDoesNotExist()
            compose.onNode(hasText("Save my answer") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(10_000) { model.openShift?.events?.any { it.type == ShiftEventType.CANT_STOP } == true }
            // The confirmation's heading. Just after the reset the break is half an hour off, past the 10-minute snooze, so
            // the heading names the break's own time, as the panel does (fix round 1, H1: never the snooze's time).
            awaitNode(hasText("Next break due at", substring = true) and isHeading(), model)
            compose.onNodeWithText("Nothing was added to your breaks.").assertExists()
            compose.onNode(hasText("Return to Today") and hasClickAction()).performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            awaitNode(hasTestTag("today_line_next") and hasText("Can't stop yet", substring = true), model)

            // End shift asks first, then writes the log and opens the summary.
            compose.onNodeWithTag("shift_end").performScrollTo().performClick()
            compose.onNodeWithTag("shift_end_confirm").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Shift summary"), model)
            compose.waitUntil(10_000) { model.openShift == null }
            val saved = runBlocking { requireNotNull(dao.getSession("local", shiftId)) }
            assertNotNull(saved.endedAt)
            assertEquals(ShiftSource.TRACKED, saved.source)
            assertEquals(ShiftStatus.NONE, saved.status)
            // Only the timed break counts: the answer adds nothing, and a smoko or lunch time that passed is a proposal, not a break.
            assertTrue("Only the timed break counts: ${saved.breakMinutes}", saved.breakMinutes in 11..12)
            assertTrue("The shift ran about 25 minutes plus the test: ${saved.outdoorMinutes}", saved.outdoorMinutes in 14..30)
            assertEquals(0, saved.breakMinutes - ShiftRules.summarise(ShiftRules.instant(requireNotNull(saved.startedAt)),
                ShiftRules.instant(requireNotNull(saved.endedAt)), runBlocking { dao.getEvents(shiftId) }, emptyList()).breakMinutes)
            compose.onNodeWithTag("summary_worked").assertExists()
            compose.onNodeWithTag("summary_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("shift_start").assertExists()

            compose.onNodeWithTag("nav_records").assertIsEnabled().performClick()
            // This shift's card by its derived minutes too: the local profile may already hold a shift of the same work.
            awaitNode(hasText(saved.title) and hasText("${Formats.duration(saved.outdoorMinutes)} worked · " +
                "${Formats.duration(saved.breakMinutes)} breaks", substring = true), model)
            scenario.onActivity { model.signOut() }
            compose.waitUntil(10_000) { model.ownerId == null && model.sessions.isEmpty() }
            awaitNode(hasText("Get started") and hasClickAction(), model)
            assertEquals(saved, runBlocking { dao.getSession("local", shiftId) })
            compose.onNodeWithText("Get started").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.loading && model.sessions.any { it.id == shiftId } }
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
        } finally { scenario.close() }
    }

    @Test
    fun coldStartGoesWelcomeSetupTodayAndRelaunchOpensToday() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.deletePlan("local")
            // Royal Park too, because the park-switch steps below move Today there before the 38° rule check.
            dao.saveForecast(todayForecast("Birrarung Marr"))
            dao.saveForecast(todayForecast("Royal Park"))
        }
        deleteLocalPreferences(database)
        // Real cold construction catches property/delegate placement errors before init -> switchOwner.
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var model = modelOf(scenario)
            awaitNode(hasText("Get started") and hasClickAction(), model)
            compose.onNodeWithTag("app_title").assertDoesNotExist()
            compose.onNodeWithTag("nav_today").assertDoesNotExist()
            compose.onNodeWithText("Get started").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Your usual day"), model)
            assertEquals("local", model.ownerId)
            // A brand-new owner has no earlier settings or log, so setup says nothing about keeping them.
            assertFalse(model.upgradingOwner)
            compose.onNodeWithText("suggested job", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("nav_today").assertDoesNotExist()
            compose.onNodeWithTag("setup_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            assertFalse(model.needsSetup)
            assertTrue(runBlocking { dao.getPreferences("local") }?.setupDone == true)
            // The hero shows today's peak from the seeded row or, if the network answered first, the live one.
            compose.waitUntil(20_000) {
                val peak = model.todayHeat.peak
                peak != null && compose.onAllNodes(hasText(Formats.temperature(peak.heat))).fetchSemanticsNodes().isNotEmpty()
            }

            // Review Focus 8: tapping the tab you're under goes back to its top screen (spec §4). From a fresh
            // start, Plan and Start shift both belong to Today, so tapping Today shows Today. It must not restore
            // the screen just left. This needs no shift running for the local owner, as on the dedicated test AVD.
            compose.onNode(hasText("Plan work windows") and hasClickAction()).performScrollTo().performClick()
            awaitTitleOtherThan("Today")
            compose.onNodeWithTag("nav_today").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            // Pre-filled from the usual day, so Confirm is ready at any hour, after the usual finish too.
            compose.onNodeWithTag("start_shift_confirm").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("nav_today").assertIsSelected().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)

            // Today's park switcher (Task 8 selectSite) changes only the park in the stored row. A plan saved for
            // today at the old park no longer applies, so it is deleted and a neutral banner says so.
            runBlocking { dao.savePlan(ShiftPlan(ownerId = "local", site = "Birrarung Marr")) }
            compose.waitUntil(10_000) { model.plan?.site == "Birrarung Marr" }
            val beforeSwitch = runBlocking { requireNotNull(dao.getPreferences("local")) }
            scenario.onActivity { model.selectSite("Royal Park") }
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.site == "Royal Park" }
            val afterSwitch = runBlocking { requireNotNull(dao.getPreferences("local")) }
            assertEquals(beforeSwitch.task, afterSwitch.task)
            assertEquals(beforeSwitch.shiftStartMinute, afterSwitch.shiftStartMinute)
            assertEquals(beforeSwitch.shiftEndMinute, afterSwitch.shiftEndMinute)
            assertEquals(beforeSwitch.hotDayTrigger, afterSwitch.hotDayTrigger, 0.0)
            assertTrue(afterSwitch.setupDone)
            // selectSite sets the message after its transaction, so once it is set the plan is already gone.
            compose.waitUntil(10_000) { model.message != null }
            assertNull(runBlocking { dao.getPlan("local") })
            // R is the app's heatshield.R: this test shares its package.
            assertEquals(UiMessage(context.getString(R.string.today_plan_cleared, "Birrarung Marr"), isError = false),
                model.message)
            awaitNode(hasTestTag("app_message"), model)
            compose.onNode(hasText("Dismiss") and hasClickAction() and hasAnyAncestor(hasTestTag("app_message"))).performClick()
            compose.waitUntil(10_000) { model.message == null }
            // Today follows the new park: the reasoner's site, and the switcher's "<day> · <park>" text.
            compose.waitUntil(10_000) { model.todayHeat.site == "Royal Park" }
            awaitNode(hasTestTag("park_switcher") and SemanticsMatcher("text ends with Royal Park") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.endsWith("Royal Park") }
            }, model)

            // The January sensor replay has left Today: no replay card, row counter or playback buttons.
            // Exact, case-sensitive text, so ordinary words such as "paused" or "tomorrow " never match.
            listOf("Historical sensor replay", "Pause replay", "Resume replay", "Next sensor reading", "Replay from shift start")
                .forEach { compose.onAllNodes(hasText(it)).assertCountEquals(0) }
            compose.onAllNodes(hasText("Row ", substring = true)).assertCountEquals(0)

            // Profile saves the hot-day rule at once, and Today's crew line follows it.
            compose.onNodeWithTag("nav_profile").performClick()
            compose.onNode(hasText("38°") and hasClickAction()).performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.hotDayTrigger == 38.0 }
            compose.onNodeWithTag("nav_today").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("38°", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }

            // Each tab keeps its own place: Log › Search is still open after a trip to Today.
            compose.onNodeWithTag("nav_records").performClick()
            compose.onNode(hasText("Search") and hasClickAction()).performClick()
            awaitNode(hasTestTag("app_title") and hasText("Search log"), model)
            compose.onNode(hasText("Work type, park or note") and hasSetTextAction()).performTextInput("Royal Park")
            compose.onNodeWithTag("nav_today").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("nav_records").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Search log"), model)
            compose.onNode(hasText("Work type, park or note") and hasSetTextAction()).assertTextContains("Royal Park")

            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithText("Get started").assertDoesNotExist()
            compose.onNodeWithText("Your usual day").assertDoesNotExist()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun upgradedOwnerConfirmsUsualWorkBeforeTodayAndKeepsTheirLog() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val title = "Upgrade check ${UUID.randomUUID().toString().take(8)}"
        val before = UserPreferences(ownerId = "local", site = "Carlton Gardens", effort = "Moderate effort",
            clothing = "Standard workwear", reminders = false, setupDone = false)
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        runBlocking {
            // A v1 row retains setupDone=false: the schema default task is not a confirmed choice.
            dao.savePreferences(before)
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Carlton Gardens"))
            dao.saveSession(WorkSession(ownerId = "local", title = title, site = "Carlton Gardens",
                date = LocalDate.now(HeatShieldViewModel.MELBOURNE).format(HeatShieldViewModel.RECORD_DATE),
                outdoorMinutes = 90, breakMinutes = 10, status = ShiftStatus.NONE))
        }
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Your usual day"), model)
            assertEquals(before, runBlocking { dao.getPreferences("local") })
            // Only an upgraded owner is told their settings were kept and why the job was suggested.
            assertTrue(model.upgradingOwner)
            compose.onNodeWithText("suggested job", substring = true).assertExists()
            assertEquals("Moderate effort", heatshield.domain.ContextReasoner.usualEffort(model.preferences).label)
            compose.onNodeWithTag("nav_today").assertDoesNotExist()
            compose.onNodeWithTag("setup_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val after = requireNotNull(runBlocking { dao.getPreferences("local") })
            assertTrue(after.setupDone)
            assertEquals("Moderate effort", after.effort)
            assertEquals(before.site, after.site)
            assertEquals(before.clothing, after.clothing)
            assertEquals(before.reminders, after.reminders)
            assertEquals(before.hotDayTrigger, after.hotDayTrigger, 0.0)
            assertEquals(before.shiftStartMinute, after.shiftStartMinute)
            assertEquals(before.shiftEndMinute, after.shiftEndMinute)
            assertEquals(after.effort, WorkTasks.effortFor(after.task).label)
            compose.onNodeWithTag("nav_records").performClick()
            awaitNode(hasText(title), model)
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            assertEquals(after, runBlocking { dao.getPreferences("local") })
        } finally {
            scenario.close()
            runBlocking {
                dao.observeSessions("local").first().filter { it.title == title }.forEach { dao.deleteSession("local", it.id) }
            }
        }
    }

    // Review Focus 7 (spec §5.5): a Profile save that changes the usual hours re-validates a saved plan for today.
    // The plan is deleted and Today stops showing its window. A save that leaves the usual day alone keeps both, even
    // though this plan's heavy work and clothing differ from the usual light day. (M3 retired the plan's own reminder.)
    @Test
    fun changingUsualHoursDropsTodaysPlan() = withLocalOwnerRestored { database ->
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", task = "Irrigation & checks",
                effort = "Light effort", reminders = true, setupDone = true))
            dao.saveForecast(todayForecast("Birrarung Marr"))
            // Today's window at the usual park, planned as heavy work in heavy protective clothing, due in 3 hours.
            dao.savePlan(ShiftPlan(ownerId = "local", work = "Digging & planting", effort = "Heavy effort",
                clothing = "Heavy protective clothing", site = "Birrarung Marr", window = "07:00–09:00",
                reminderAt = System.currentTimeMillis() + 3 * 3_600_000L))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasText("Get started") and hasClickAction(), model)
            compose.onNodeWithText("Get started").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            awaitNode(hasTestTag("today_line_plan"), model)

            // The hot-day rule is not part of the usual day: the plan and its window stay.
            scenario.onActivity { model.updatePreferences { it.copy(hotDayTrigger = 38.0) } }
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.hotDayTrigger == 38.0 }
            assertEquals("07:00–09:00", runBlocking { dao.getPlan("local") }?.window)
            compose.waitForIdle()
            compose.onNodeWithTag("today_line_plan").assertExists()

            // New usual hours, 10 am – 6 pm. The same transaction deletes the plan.
            scenario.onActivity { model.updatePreferences { it.copy(shiftStartMinute = 600, shiftEndMinute = 1080) } }
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.shiftStartMinute == 600 }
            assertEquals(1080, runBlocking { dao.getPreferences("local") }?.shiftEndMinute)
            assertNull(runBlocking { dao.getPlan("local") })
            compose.waitUntil(10_000) { model.plan == null }
            compose.waitForIdle()
            compose.onNodeWithTag("today_line_plan").assertDoesNotExist()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun siteOnlyProfileSaveKeepsFuturePlanButWorkConditionChangeInvalidatesIt() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val tomorrow = LocalDate.now(HeatShieldViewModel.MELBOURNE).plusDays(1)
        val futurePlan = ShiftPlan(ownerId = "local", site = "Birrarung Marr", work = "Digging & planting",
            effort = "Heavy effort", clothing = "Standard workwear", window = "07:00–09:00",
            dayMillis = tomorrow.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            reminderAt = tomorrow.atTime(6, 55).atZone(HeatShieldViewModel.MELBOURNE).toInstant().toEpochMilli())
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            assertEquals(tomorrow, futurePlan.context.date) // DatePicker dayMillis is UTC midnight, not a Melbourne instant.
            dao.savePlan(futurePlan)
            dao.saveForecast(todayForecast("Birrarung Marr"))
            dao.saveForecast(todayForecast("Royal Park"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE)
            .edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.waitUntil(10_000) { model.plan == futurePlan }
            // Actual Profile public save path, not the private predicate reproduced in a test.
            scenario.onActivity { model.updatePreferences { it.copy(site = "Royal Park") } }
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.site == "Royal Park" }
            assertEquals(futurePlan, runBlocking { dao.getPlan("local") })
            assertEquals(futurePlan, model.plan)
            // The same future plan must still be invalidated by a genuine work-condition change.
            scenario.onActivity { model.updatePreferences { it.copy(shiftStartMinute = 480) } }
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.shiftStartMinute == 480 }
            assertNull(runBlocking { dao.getPlan("local") })
            compose.waitUntil(10_000) { model.plan == null }
        } finally {
            scenario.close()
        }
    }

    // Spec §5.5: a plan whose day has passed no longer applies. Plan opens on today and the usual work, not on the
    // old plan's date, work and park, and has no saved window to clear.
    @Test
    fun aPastPlanNoLongerStartsPlan() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val today = LocalDate.now(HeatShieldViewModel.MELBOURNE)
        val yesterday = today.minusDays(1)
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", task = "Irrigation & checks",
                effort = "Light effort", setupDone = true))
            dao.savePlan(ShiftPlan(ownerId = "local", site = "Royal Park", work = "Push mowing", effort = "Heavy effort",
                clothing = "Heavy protective clothing", window = "07:00–09:00",
                dayMillis = yesterday.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()))
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.waitUntil(10_000) { model.plan?.context?.date == yesterday }
            compose.onNode(hasText("Plan work windows") and hasClickAction()).performScrollTo().performClick()
            awaitNode(hasText("Step 1 of 2 · Your day"), model)
            compose.onNode(hasText("Work date · ${Formats.fullDate(today)}") and hasClickAction()).assertExists()
            compose.onNode(hasText("Work type") and hasText("Irrigation & checks · light work")).assertExists()
            compose.onNode(hasText("Park") and hasText("Birrarung Marr")).assertExists()
            compose.onNodeWithText("Clear saved window").assertDoesNotExist()
        } finally {
            scenario.close()
        }
    }

    // A break on today's shift, and ending the shift, leave a window saved for tomorrow alone.
    @Test
    fun finishingABreakTodayKeepsTomorrowsPlan() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val today = LocalDate.now(HeatShieldViewModel.MELBOURNE)
        val tomorrow = today.plusDays(1)
        val futurePlan = ShiftPlan(ownerId = "local", site = "Birrarung Marr", work = "Digging & planting",
            effort = "Heavy effort", window = "07:00–09:00",
            dayMillis = tomorrow.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            reminderAt = tomorrow.atTime(7, 0).atZone(HeatShieldViewModel.MELBOURNE).toInstant().toEpochMilli())
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", reminders = true, setupDone = true))
            dao.savePlan(futurePlan)
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.waitUntil(10_000) { model.plan == futurePlan }
            assumeTrue("The dedicated local test profile has no shift running.", model.openShift == null)
            val start = ShiftRules.localTime(System.currentTimeMillis())
            val started = AtomicBoolean(false)
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Irrigation & checks", heavyWork = false, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) { started.set(true) }
            }
            compose.waitUntil(10_000) { started.get() && !model.busy && model.shiftState is ShiftUiState.On }
            val shiftId = requireNotNull(model.openShift).shift.id
            scenario.onActivity { model.startBreak() }
            compose.waitUntil(10_000) { !model.busy && model.shiftState is ShiftUiState.OnBreak }
            scenario.onActivity { model.endBreak() }
            compose.waitUntil(10_000) { !model.busy && model.shiftState is ShiftUiState.On }
            val endedId = AtomicInteger(0)
            scenario.onActivity { model.endShift { endedId.set(it) } }
            compose.waitUntil(10_000) { endedId.get() != 0 && !model.busy && model.openShift == null }
            assertEquals(shiftId, endedId.get())
            val ended = runBlocking { requireNotNull(dao.getSession("local", shiftId)) }
            assertNotNull(ended.endedAt)
            assertEquals(ShiftStatus.NONE, ended.status)
            assertEquals(1, runBlocking { dao.getEvents(shiftId) }.count { it.type == ShiftEventType.BREAK && it.endedAt != null })
            assertEquals(futurePlan, runBlocking { dao.getPlan("local") })
            compose.waitForIdle()
            assertEquals(futurePlan.reminderAt, model.plan?.reminderAt)
        } finally {
            scenario.close()
        }
    }

    // Room shows a just-fetched forecast before the 1 s ticker moves. Today judges it from the moment it arrived; a
    // fetch time ahead of the clock reads as stale and would flash "This forecast doesn't cover today."
    @Test
    fun aJustFetchedForecastIsNeverJudgedStale() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.waitUntil(20_000) { model.todayHeat.peak != null }
            val seededAt = System.currentTimeMillis()
            val judged = Collections.synchronizedList(mutableListOf<Freshness>())
            // Every forecast Today publishes from here on, judged at the moment it is published.
            scenario.onActivity {
                watcher.launch {
                    snapshotFlow { model.forecast }.collect { forecast ->
                        if (forecast != null && forecast.fetchedAt >= seededAt) judged += model.todayHeat.freshness
                    }
                }
            }
            runBlocking { dao.saveForecast(todayForecast("Birrarung Marr")) }
            compose.waitUntil(10_000) { judged.isNotEmpty() }
            assertFalse("A just-fetched forecast was judged stale: $judged", Freshness.STALE in judged)
        } finally {
            watcher.cancel()
            scenario.close()
        }
    }

    /**
     * A shift forgotten at another park and found overdue as the app reopens (the normal way a forgotten shift is found)
     * is summarised from that park's cached forecast, not from the empty state before Room's first read: its peak and
     * per-level minutes are stored, and the notice names the time it ended.
     */
    @Test
    fun aForgottenShiftEndedOnRelaunchKeepsItsHeatFromTheCachedForecast() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val plannedEnd = System.currentTimeMillis() - 3 * 3_600_000L
        val startedAt = plannedEnd - 2 * 3_600_000L
        val shiftId = runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Royal Park"))
            dao.startShift(WorkSession(ownerId = "local", title = "Push mowing", site = "Royal Park",
                date = ShiftRules.localTime(startedAt).toLocalDate().format(HeatShieldViewModel.RECORD_DATE), outdoorMinutes = 0, breakMinutes = 0,
                status = ShiftStatus.NONE, startedAt = startedAt, plannedEndAt = plannedEnd, source = ShiftSource.TRACKED, heavyWork = true)).toInt()
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            compose.waitUntil(20_000) { runBlocking { dao.getSession("local", shiftId) }?.endedAt != null }
            val saved = runBlocking { requireNotNull(dao.getSession("local", shiftId)) }
            assertTrue(saved.autoEnded)
            assertEquals(plannedEnd, saved.endedAt)
            assertEquals(120, saved.outdoorMinutes)
            assertNotNull("No peak: the shift was summarised before its park's forecast was read", saved.peakLevel)
            assertEquals(saved.outdoorMinutes, saved.normalMinutes + saved.warmMinutes + saved.hotMinutes + saved.veryHotMinutes + saved.extremeMinutes)
            compose.waitUntil(10_000) { model.message != null }
            assertEquals(UiMessage(context.getString(R.string.shift_auto_ended_notice, Formats.time(ShiftRules.localTime(plannedEnd).toLocalTime(),
                android.text.format.DateFormat.is24HourFormat(context))), isError = false), model.message)
        } finally {
            scenario.close()
        }
    }

    /**
     * Astra M2 #6: comparing another park on Plan replaces only the forecast on show. The running shift keeps its own
     * park's forecast, so it stays timed, and End shift summarises from that park's cache.
     */
    @Test
    fun comparingAnotherParkKeepsTheShiftOnItsOwnParksForecast() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
            dao.saveForecast(todayForecast("Royal Park"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            // Started half an hour ago ("Started earlier?"), so End shift a few seconds later still spans whole minutes
            // and a forecast hour whatever the wall clock reads (fix round 1, M2).
            val start = ShiftRules.localTime(System.currentTimeMillis() - 30 * 60_000L)
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(20_000) { (model.shiftState as? ShiftUiState.On)?.evaluation?.current != null }
            val shiftId = requireNotNull(model.openShift).shift.id
            // Plan's comparison of another park, then the Log: the display forecast is Royal Park's.
            scenario.onActivity { model.previewForecast("Royal Park") }
            compose.waitUntil(10_000) { model.forecast?.site == "Royal Park" }
            compose.onNodeWithTag("nav_records").performClick()
            awaitTitleOtherThan("Today")
            val evaluation = (model.shiftState as ShiftUiState.On).evaluation
            assertNotNull("The shift lost its park's forecast: ${evaluation.due}", evaluation.current)
            assertFalse(evaluation.due is heatshield.domain.BreakDue.NoForecast)
            scenario.onActivity { model.endShift {} }
            compose.waitUntil(10_000) { model.openShift == null && !model.busy }
            val saved = runBlocking { requireNotNull(dao.getSession("local", shiftId)) }
            assertNotNull("No peak: End shift read the forecast on show, not the shift's park", saved.peakLevel)
        } finally {
            scenario.close()
        }
    }

    /**
     * A new Melbourne day applies the saved park without a preferences, plan or shift write at midnight.
     * Hidden, the screen's clock is stopped at 23:59; a reconcile, which every ForecastWorker run and alarm ends with,
     * reads the real date and moves background refresh to the plan's park on its own. Shown again, the ticker moves the
     * owner forecast, and the next unchanged seconds keep the same periodic-work generation.
     */
    @Test
    fun midnightRetargetsTheOwnerForecastAndWorkerWithoutARowWrite() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
            dao.saveForecast(aroundTodayForecast("Royal Park"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("nav_records").performClick()
            awaitTitleOtherThan("Today")
            compose.waitUntil(45_000) { !model.refreshing && model.ownerForecast?.site == "Birrarung Marr" }
            val today = LocalDate.now(MelbourneZone)
            scenario.onActivity {
                model.setVisible(false)
                setModelNow(model, today.minusDays(1).atTime(23, 59).atZone(MelbourneZone).toInstant().toEpochMilli())
            }
            val plan = ShiftPlan(ownerId = "local", site = "Royal Park",
                dayMillis = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            runBlocking { dao.savePlan(plan) }
            compose.waitUntil(10_000) { model.plan == plan }
            assertEquals("Birrarung Marr", model.context.site)
            assertEquals("Birrarung Marr", model.ownerForecast?.site)
            val worker = WorkManager.getInstance(context) as androidx.work.impl.WorkManagerImpl
            val previous = worker.getWorkInfosForUniqueWork("heatshield_forecast").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }

            // Still hidden: a background reconcile alone points the periodic work at Royal Park; the stopped screen stays.
            runBlocking { AlertCoordinator(context).reconcile() }
            compose.waitUntil(10_000) {
                worker.workDatabase.workSpecDao().getWorkSpec(previous.id.toString())?.input?.getString("site_name") == "Royal Park"
            }
            assertEquals("Birrarung Marr", model.ownerForecast?.site)
            val updated = worker.getWorkInfosForUniqueWork("heatshield_forecast").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }

            // The real ticker now crosses the simulated midnight. No owner row changes after this point.
            scenario.onActivity { model.setVisible(true) }
            compose.waitUntil(10_000) { model.ownerForecast?.site == "Royal Park" }
            val tick = model.now
            compose.waitUntil(5_000) { model.now >= tick + 2_000 }
            val unchanged = worker.getWorkInfosForUniqueWork("heatshield_forecast").get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
            assertEquals(updated.generation, unchanged.generation)
            assertEquals(plan, runBlocking { dao.getPlan("local") })
        } finally {
            scenario.close()
        }
    }

    /**
     * Ending Royal Park work returns Today to Birrarung Marr, but Summary and Fix times still read Royal Park's cache.
     * Shortening the saved outdoor span must also shorten its minutes at heat levels; near midnight the span stays today.
     */
    @Test
    fun endedShiftKeepsItsOwnCachedHeatForSummaryAndTimeCorrections() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
            dao.saveForecast(aroundTodayForecast("Royal Park"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val wall = System.currentTimeMillis()
            val midnight = java.time.Instant.ofEpochMilli(wall).atZone(MelbourneZone).toLocalDate()
                .atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
            val start = ShiftRules.localTime(maxOf(wall - 30 * 60_000L, midnight))
            scenario.onActivity {
                model.startShift(StartShiftInput("Royal Park", "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(20_000) { (model.shiftState as? ShiftUiState.On)?.evaluation?.current != null && !model.busy }
            val id = requireNotNull(model.openShift).shift.id
            compose.waitUntil(45_000) { !model.refreshing }
            WorkManager.getInstance(context).cancelUniqueWork("heatshield_forecast").result.get(10, TimeUnit.SECONDS)
            runBlocking { dao.saveForecast(aroundTodayForecast("Royal Park")) }
            compose.waitUntil(10_000) { model.ownerForecast?.hours?.all { it.temperature == 33.0 } == true }
            scenario.onActivity { model.endShift {} }
            compose.waitUntil(10_000) { model.openShift == null && !model.busy && model.ownerForecast?.site == "Birrarung Marr" }
            compose.waitUntil(10_000) { model.forecast?.site == "Birrarung Marr" }
            compose.onNodeWithTag("nav_records").performClick()
            awaitTitleOtherThan("Today")
            val shown = runBlocking { model.observeShift(id).first { it != null } }!!
            assertTrue("Summary discarded Royal Park's saved hours", model.hoursFor(shown.shift).isNotEmpty())
            assertTrue("Fix times reported Royal Park's saved forecast missing", model.recalculationHoursFor(shown.shift).isNotEmpty())
            val startedAt = requireNotNull(shown.shift.startedAt)
            val endedAt = requireNotNull(shown.shift.endedAt)
            val reduction = minOf(10 * 60_000L, (endedAt - startedAt) / 2)
            val correctedEnd = ShiftRules.localTime(endedAt - reduction)
            val edit = heatshield.data.ShiftTimesEdit(ShiftRules.localTime(requireNotNull(shown.shift.startedAt)), correctedEnd, emptyList(), null)
            scenario.onActivity { model.fixTimes(id, edit) {} }
            compose.waitUntil(10_000) { !model.busy }
            assertNull(model.message)
            val corrected = runBlocking { requireNotNull(dao.getSession("local", id)) }
            assertEquals(((endedAt - reduction - startedAt) / 60_000L).toInt(), corrected.outdoorMinutes)
            assertEquals(corrected.outdoorMinutes, corrected.normalMinutes + corrected.warmMinutes + corrected.hotMinutes +
                corrected.veryHotMinutes + corrected.extremeMinutes)
        } finally {
            scenario.close()
        }
    }

    /**
     * A posted break alert updates the running shift while Plan keeps its different park and selected window.
     * Birrarung Marr remains the live shift's forecast; Royal Park tomorrow retains its chosen 10:00–12:00 comparison.
     */
    @Test
    fun aBreakAlertKeepsAnotherParksComparisonAndSelectedWindow() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val tomorrow = LocalDate.now(MelbourneZone).plusDays(1)
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.savePlan(ShiftPlan(ownerId = "local", site = "Royal Park", startHour = 7,
                dayMillis = tomorrow.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()))
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
            dao.saveForecast(aroundTodayForecast("Royal Park"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val start = ShiftRules.localTime(System.currentTimeMillis() - 30 * 60_000L)
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(20_000) { (model.shiftState as? ShiftUiState.On)?.evaluation?.current != null && !model.busy }
            val id = requireNotNull(model.openShift).shift.id
            compose.onNode(hasText(context.getString(R.string.today_plan)) and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.shift_plan_compare)).performScrollTo().performClick()
            compose.waitUntil(45_000) { !model.refreshing && model.forecast?.site == "Royal Park" }
            WorkManager.getInstance(context).cancelUniqueWork("heatshield_forecast").result.get(10, TimeUnit.SECONDS)
            runBlocking { dao.saveForecast(aroundTodayForecast("Royal Park")); dao.saveForecast(aroundTodayForecast("Birrarung Marr")) }
            val use24Hour = android.text.format.DateFormat.is24HourFormat(context)
            val choice = context.getString(R.string.shift_plan_window_time, Formats.time(java.time.LocalTime.of(10, 0), use24Hour),
                Formats.time(java.time.LocalTime.of(12, 0), use24Hour))
            compose.onNode(hasText(choice) and isSelectable()).performScrollTo().performClick().assertIsSelected()
            val repository = heatshield.data.ShiftRepository(database, heatshield.data.AndroidShiftClock(context))
            val previousAlerts = requireNotNull(model.openShift).events.count { it.type == ShiftEventType.ALERT }
            runBlocking {
                repository.recordAlert("local", id, heatshield.data.AlertReason.BREAK_DUE,
                    heatshield.data.EventContext(null, null, null), java.time.Instant.now())
            }
            compose.waitUntil(10_000) { (model.openShift?.events?.count { it.type == ShiftEventType.ALERT } ?: 0) > previousAlerts }
            compose.waitForIdle()
            assertEquals("Royal Park", model.forecast?.site)
            assertEquals("Birrarung Marr", model.ownerForecast?.site)
            assertNotNull((model.shiftState as ShiftUiState.On).evaluation.current)
            compose.onNode(hasText(choice) and isSelectable()).assertIsSelected()
        } finally {
            scenario.close()
        }
    }

    /**
     * Summary stays subscribed to its saved park when a missing forecast arrives, then follows refreshed hours.
     * The shift and events never change: Royal Park's absent cache becomes 33 °C, then 18 °C on the same page.
     */
    @Test
    fun savedShiftHoursFollowItsParkCacheWithoutReopeningThePage() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val endedAt = System.currentTimeMillis() - 30 * 60_000L
        val startedAt = endedAt - 60 * 60_000L
        val id = runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
            dao.deleteForecast("Royal Park")
            dao.saveSession(WorkSession(ownerId = "local", title = "Push mowing", site = "Royal Park",
                date = ShiftRules.localTime(startedAt).toLocalDate().format(HeatShieldViewModel.RECORD_DATE),
                outdoorMinutes = 60, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = startedAt, endedAt = endedAt,
                plannedEndAt = endedAt, source = ShiftSource.TRACKED, heavyWork = true)).toInt()
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("nav_records").performClick()
            awaitTitleOtherThan("Today")
            val shown = java.util.concurrent.atomic.AtomicReference<heatshield.domain.OpenShift?>(null)
            scope.launch { model.observeShift(id).collect { shown.set(it) } }
            compose.waitUntil(10_000) { shown.get() != null }
            val shift = requireNotNull(shown.get()).shift
            assertTrue(model.hoursFor(shift).isEmpty())

            val hotCache = aroundTodayForecast("Royal Park")
            runBlocking { dao.saveForecast(hotCache) }
            compose.waitUntil(10_000) { model.hoursFor(shift).isNotEmpty() }
            assertTrue(model.hoursFor(shift).all { it.airC == 33.0 })
            val coolHours = Gson().fromJson(hotCache.hoursJson, Array<ForecastHour>::class.java).map { it.copy(temperature = 18.0) }
            runBlocking { dao.saveForecast(hotCache.copy(hoursJson = Gson().toJson(coolHours))) }
            compose.waitUntil(10_000) { model.hoursFor(shift).isNotEmpty() && model.hoursFor(shift).all { it.airC == 18.0 } }
            assertEquals(shift, runBlocking { dao.getSession("local", id) })
        } finally {
            scope.cancel()
            scenario.close()
        }
    }

    /**
     * A rejected or busy Preview save does not acknowledge completion. Retrying the same finished result after the
     * running shift ends writes one labelled Preview shift and acknowledges exactly that successful transaction.
     */
    @Test
    fun previewSaveFailureAndBusyKeepTheResultRetryableWithoutAcknowledgingIt() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val start = ShiftRules.localTime(System.currentTimeMillis() - 30 * 60_000L)
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(20_000) { model.openShift != null && !model.busy }
            val previewStart = LocalDate.of(2026, 1, 15).atTime(9, 52)
            val result = heatshield.data.PreviewShiftResult("Push mowing", true, previewStart, previewStart.plusMinutes(28),
                emptyList(), emptyList())
            val previousPreviewIds = runBlocking { dao.observeSessions("local").first().filter { it.source == ShiftSource.PREVIEW }.map { it.id }.toSet() }
            val acknowledgements = AtomicInteger(0)

            // The real Room transaction rejects a Preview save while a real shift is open.
            scenario.onActivity { model.savePreviewShift(result) { acknowledgements.incrementAndGet() } }
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(context.getString(R.string.notice_shift_open), model.message?.text)
            assertEquals(0, acknowledgements.get())
            assertEquals(previousPreviewIds, runBlocking { dao.observeSessions("local").first().filter { it.source == ShiftSource.PREVIEW }.map { it.id }.toSet() })

            scenario.onActivity {
                model.startBreak(BreakReason.SHADE)
                assertTrue("Starting the real break holds the save action while Room answers", model.busy)
                model.savePreviewShift(result) { acknowledgements.incrementAndGet() }
            }
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(0, acknowledgements.get())
            assertEquals(previousPreviewIds, runBlocking { dao.observeSessions("local").first().filter { it.source == ShiftSource.PREVIEW }.map { it.id }.toSet() })

            scenario.onActivity { model.endShift {} }
            compose.waitUntil(10_000) { model.openShift == null && !model.busy }
            scenario.onActivity { model.savePreviewShift(result) { acknowledgements.incrementAndGet() } }
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(1, acknowledgements.get())
            val saved = runBlocking { dao.observeSessions("local").first().filter { it.source == ShiftSource.PREVIEW && it.id !in previousPreviewIds } }
            assertEquals(1, saved.size)
            assertEquals(ShiftRules.epochMillis(result.start), saved.single().startedAt)
            assertEquals(ShiftRules.epochMillis(result.end), saved.single().endedAt)
            assertEquals(28, saved.single().outdoorMinutes)
        } finally {
            scenario.close()
        }
    }

    /** Sets only the UI clock, in epoch milliseconds, so the real next tick can cross a date without changing phone time. */
    private fun setModelNow(model: HeatShieldViewModel, millis: Long) {
        val field = HeatShieldViewModel::class.java.getDeclaredField("now\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as androidx.compose.runtime.MutableState<Long>
        state.value = millis
    }

    /**
     * Astra M2 #6 (fix round 1, M3): auto-end reads the shift park's own cache, whatever forecast is on show. A shift at
     * Royal Park runs while Plan compares Birrarung Marr; then it turns out forgotten (its times move back past the grace
     * period), and it ends by itself with its heat from Royal Park's cache. Royal Park is 33° every hour and Birrarung
     * Marr under 26°, so the stored peak says which cache it read.
     */
    @Test
    fun aForgottenShiftEndsFromItsOwnParksCacheWhileAnotherParkIsOnShow() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val now = System.currentTimeMillis()
        val shiftId = runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Royal Park"))
            dao.saveForecast(todayForecast("Birrarung Marr"))
            // No monotonic anchor, so the shift's clock is the wall clock and moving its times back makes it overdue.
            dao.startShift(WorkSession(ownerId = "local", title = "Push mowing", site = "Royal Park",
                date = ShiftRules.localTime(now).toLocalDate().format(HeatShieldViewModel.RECORD_DATE), outdoorMinutes = 0, breakMinutes = 0,
                status = ShiftStatus.NONE, startedAt = now - 30 * 60_000L, plannedEndAt = now + 3 * 3_600_000L, source = ShiftSource.TRACKED,
                heavyWork = true)).toInt()
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            compose.waitUntil(20_000) { (model.shiftState as? ShiftUiState.On)?.evaluation?.current != null }
            // Today shows its own park once it has composed (its LaunchedEffect calls previewForecast with the shift's
            // park). Wait for it first, so that late first effect cannot replace the comparison below.
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.waitForIdle()
            // The foreground refreshes settle and background refresh stops, then both caches are seeded again, so the
            // network cannot replace either park's hours during the check.
            compose.waitUntil(45_000) { !model.refreshing }
            WorkManager.getInstance(context).cancelUniqueWork("heatshield_forecast").result.get(10, TimeUnit.SECONDS)
            runBlocking { dao.saveForecast(aroundTodayForecast("Royal Park")); dao.saveForecast(todayForecast("Birrarung Marr")) }
            scenario.onActivity { model.previewForecast("Birrarung Marr") }
            compose.waitUntil(10_000) { model.forecast?.site == "Birrarung Marr" }
            compose.onNodeWithTag("nav_records").performClick()
            awaitTitleOtherThan("Today")
            compose.waitUntil(45_000) { !model.refreshing }
            runBlocking { dao.saveForecast(aroundTodayForecast("Royal Park")); dao.saveForecast(todayForecast("Birrarung Marr")) }
            compose.waitUntil(10_000) { model.forecast?.hours?.all { it.temperature < 26.0 } == true }
            // Forgotten: started five hours ago, planned to end three hours ago (past the two-hour grace).
            val plannedEnd = System.currentTimeMillis() - 3 * 3_600_000L
            runBlocking {
                val open = requireNotNull(dao.getSession("local", shiftId))
                dao.updateSession("local", open.copy(startedAt = plannedEnd - 2 * 3_600_000L, plannedEndAt = plannedEnd))
            }
            compose.waitUntil(20_000) { runBlocking { dao.getSession("local", shiftId) }?.endedAt != null }
            val saved = runBlocking { requireNotNull(dao.getSession("local", shiftId)) }
            assertTrue(saved.autoEnded)
            assertEquals(plannedEnd, saved.endedAt)
            assertEquals(120, saved.outdoorMinutes)
            assertNotNull("No peak: the shift was summarised without its own park's forecast", saved.peakHeat)
            assertTrue("Peak ${saved.peakHeat}° is not Royal Park's 33°", requireNotNull(saved.peakHeat) >= 32.5)
            assertEquals(saved.outdoorMinutes, saved.normalMinutes + saved.warmMinutes + saved.hotMinutes + saved.veryHotMinutes + saved.extremeMinutes)
        } finally {
            scenario.close()
        }
    }

    /**
     * The break sheet in the real NavHost: first aid and Back keep the chosen answer and save nothing; "I don't feel
     * right" opens first aid at once and saves an unwell break.
     */
    @Test
    fun breakSheetFirstAidKeepsTheChoiceAndIDontFeelRightSavesAnUnwellBreak() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val start = ShiftRules.localTime(System.currentTimeMillis())
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Irrigation & checks", heavyWork = false, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(10_000) { !model.busy && model.shiftState is ShiftUiState.On }
            val shiftId = requireNotNull(model.openShift).shift.id
            compose.onNodeWithTag("shift_break_sheet").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Break"), model)
            compose.onNode(hasText("No shade here") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithTag("break_first_aid").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), model)
            androidx.test.espresso.Espresso.pressBack()
            awaitNode(hasTestTag("app_title") and hasText("Break"), model)
            compose.onNode(hasText("No shade here") and hasClickAction()).assertIsSelected()
            assertTrue("First aid saved nothing", runBlocking { dao.getEvents(shiftId) }.isEmpty())
            compose.onNodeWithTag("break_unwell").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), model)
            compose.waitUntil(10_000) { runBlocking { dao.getEvents(shiftId) }.isNotEmpty() }
            val unwell = runBlocking { dao.getEvents(shiftId) }.single()
            assertEquals(heatshield.data.BreakReason.UNWELL, unwell.reason)
            assertNull(unwell.endedAt)
        } finally {
            scenario.close()
        }
    }

    /**
     * M3 Task 9 fix round 1 (spec §10 M3 acceptance walk, "Unwell opens first aid with no trampoline, from a cold start
     * too, and starts the unwell break once the shift has loaded"): the break-due alert's Unwell is the activity
     * PendingIntent the production builder puts on it. Sent with no activity running (cold: a new activity and ViewModel
     * that have not read the open shift yet) and again while that activity runs (warm: it takes the tap in onNewIntent),
     * it opens Heat first aid and starts an `unwell` break on the shift the alert names. The cold request must wait for
     * the shift to load rather than be consumed as a no-op. The warm half uses the activity the action started, not the
     * scenario's: ActivityScenario hosts its activity in a task of its own, which the action's NEW_TASK | CLEAR_TOP |
     * SINGLE_TOP does not reach (it opens the app's task, as from the lock screen). The test sends the action as the shade
     * does; Android 14+ asks a sender to opt in before its PendingIntent may start an activity.
     */
    @Test
    fun unwellOnTheBreakDueAlertOpensFirstAidAndStartsAnUnwellBreakWarmAndCold() = withLocalOwnerRestored { database ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var scenarioClosed = false
        var coldActivity: MainActivity? = null
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            settleAndSeed(database, model, airC = 33.0)
            val start = ShiftRules.localTime(System.currentTimeMillis())
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Irrigation & checks", heavyWork = false, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(10_000) { !model.busy && model.shiftState is ShiftUiState.On }
            val shiftId = requireNotNull(model.openShift).shift.id
            val evaluation = (model.shiftState as ShiftUiState.On).evaluation
            val due = requireNotNull(evaluation.due as? BreakDue.At) { "33° all day times the next break: ${evaluation.due}" }
            val alert = ShiftNotifications(context).breakDue("local", shiftId, due, evaluation, evaluation.current,
                model.preferences.hotDayTrigger, repeat = false, rule = evaluation.rule)
            assertEquals(listOf("Start break", "In 10 min", "Unwell"), alert.actions.map { it.title.toString() })
            val unwell = alert.actions.last().actionIntent
            if (Build.VERSION.SDK_INT >= 31) assertTrue("Unwell opens the activity itself: no trampoline", unwell.isActivity)
            fun unwellBreaks() = runBlocking { dao.getEvents(shiftId) }.filter { it.reason == BreakReason.UNWELL }.sortedBy { it.startedAt }

            // Cold: the scenario's activity and its ViewModel are gone, so the activity the action starts (in the app's own
            // task, as from the lock screen) has not read the open shift yet; the request waits for it.
            scenario.close()
            scenarioClosed = true
            assertTrue("No unwell break before the tap", unwellBreaks().isEmpty())
            sendAsTheShadeDoes(unwell)
            compose.waitUntil(15_000) { resumedMainActivity(instrumentation).also { coldActivity = it } != null }
            val activity = requireNotNull(coldActivity)
            lateinit var coldModel: HeatShieldViewModel
            instrumentation.runOnMainSync { coldModel = ViewModelProvider(activity)[HeatShieldViewModel::class.java] }
            assertTrue("A new ViewModel, not the closed activity's", coldModel !== model)
            awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), coldModel)
            compose.waitUntil(15_000) { unwellBreaks().size == 1 }
            assertTrue(coldModel.shiftLoaded)
            assertEquals(ShiftEventType.BREAK, unwellBreaks().single().type)
            assertNull(unwellBreaks().single().endedAt)
            compose.waitUntil(10_000) { !coldModel.busy && coldModel.shiftState is ShiftUiState.OnBreak }

            // Warm: back on Today with the shift running again, the same activity takes the next tap in onNewIntent. The break
            // sheet is open when it arrives (the gardener had tapped "Can't stop, or no shade?"): first aid opens over Today, not
            // over the sheet, so Back lands on Today rather than on a sheet asking about a break the gardener is now on.
            instrumentation.runOnMainSync { coldModel.endBreak() }
            compose.waitUntil(10_000) { !coldModel.busy && coldModel.shiftState is ShiftUiState.On }
            androidx.test.espresso.Espresso.pressBack()
            awaitNode(hasTestTag("app_title") and hasText("Today"), coldModel)
            compose.onNodeWithTag("shift_break_sheet").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Break"), coldModel)
            sendAsTheShadeDoes(unwell)
            awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), coldModel)
            compose.waitUntil(10_000) { unwellBreaks().size == 2 }
            assertTrue("The running activity took the tap", resumedMainActivity(instrumentation) === activity)
            assertNotNull(unwellBreaks().first().endedAt)
            assertNull(unwellBreaks().last().endedAt)
            compose.waitUntil(10_000) { coldModel.shiftState is ShiftUiState.OnBreak }
            androidx.test.espresso.Espresso.pressBack()
            awaitNode(hasTestTag("app_title") and hasText("Today"), coldModel)
        } finally {
            coldActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            if (!scenarioClosed) scenario.close()
        }
    }

    /**
     * Follows the first alerts question through a running shift, its changed break alarm and its end. The usual day
     * stays 07:00–15:00; a run at 22:30 picks 02:00 tomorrow as this shift's finish, without saving overnight usual hours.
     */
    @Test
    fun startShiftAsksForAlertsOnceThenTheTimerAndAlarmFollowTheShift() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = ShiftNotifications(context)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences(AlertCoordinator.PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(AlertCoordinator.askedKey("local")).commit()
        runBlocking { database.dao().deletePlan("local"); database.dao().saveForecast(todayForecast("Birrarung Marr")) }
        deleteLocalPreferences(database)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasText("Get started") and hasClickAction(), model)
            compose.onNodeWithText("Get started").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Your usual day"), model)
            compose.onNodeWithTag("setup_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            assumeTrue("The dedicated local test profile has no shift running.", model.openShift == null)
            // Usual hours must finish on the same day. Only the running shift may cross midnight.
            val away = (ShiftRules.localTime(System.currentTimeMillis()).toLocalTime().toSecondOfDay() / 60 + 720) % 1440
            scenario.onActivity {
                model.updatePreferences { it.copy(shiftStartMinute = 7 * 60, shiftEndMinute = 15 * 60,
                    smokoMinute = away, lunchMinute = away) }
            }
            compose.waitUntil(10_000) {
                runBlocking { database.dao().getPreferences("local") }?.let {
                    it.shiftStartMinute == 7 * 60 && it.shiftEndMinute == 15 * 60 && it.smokoMinute == away && it.lunchMinute == away
                } == true
            }
            settleAndSeed(database, model, airC = 33.0)
            // First Start shift: the alerts intro, once. The AVD has the permission, so Allow returns at once.
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Break alerts"), model)
            compose.onNodeWithTag("alerts_allow").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            compose.waitUntil(10_000) { model.alertStatus.asked && model.preferences.reminders }
            // The default usual job is heavy; start unbumped so turning Heavy work on can move the alarm earlier.
            compose.onNode(hasText("Heavy work this shift") and hasClickAction()).performScrollTo().assertIsOn().performClick()
            // The sheet sends dated StartShiftInput times; choose a finish 3–4 hours ahead so the first break fits.
            val finish = ShiftRules.localTime(model.now).plusHours(4).withMinute(0)
            val pickerContext = context.withAppLocale()
            val use24Hour = android.text.format.DateFormat.is24HourFormat(pickerContext)
            compose.onNodeWithTag("start_shift_finish").performScrollTo().performClick()
            if (!use24Hour) {
                val period = pickerContext.getString(if (finish.hour >= 12) androidx.compose.material3.R.string.m3c_time_picker_pm
                    else androidx.compose.material3.R.string.m3c_time_picker_am)
                compose.onNode(hasText(period) and hasAnyAncestor(isDialog())).performClick()
            }
            val hour = if (use24Hour) finish.hour else (finish.hour + 11) % 12 + 1
            val hourDescription = pickerContext.getString(if (use24Hour) androidx.compose.material3.R.string.m3c_time_picker_hour_24h_suffix
                else androidx.compose.material3.R.string.m3c_time_picker_hour_suffix, hour)
            compose.onNode(hasContentDescription(hourDescription) and
                hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))).performClick()
            compose.onNodeWithContentDescription(pickerContext.getString(androidx.compose.material3.R.string.m3c_time_picker_minute_selection)).performClick()
            compose.onNode(hasContentDescription(pickerContext.getString(androidx.compose.material3.R.string.m3c_time_picker_minute_suffix, 0)) and
                hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))).performClick()
            compose.onNode(hasText("Set time") and hasClickAction()).performClick()
            compose.onNodeWithTag("start_shift_finish").assertTextContains(Formats.time(finish.toLocalTime(), use24Hour), substring = true)
            compose.onNodeWithTag("start_shift_confirm").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.openShift != null && !model.busy }
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            // Back on Today, its LaunchedEffect refreshes the park's forecast from the network, which can replace the seeded
            // 33° hours with the real weather (a cool night has no break due, so no break alarm). Let it fire and settle,
            // then seed again, as the M2 journeys do (M3 Task 9 fix round 1: this case failed on a slow AVD at 3:30 am).
            compose.waitForIdle()
            settleAndSeed(database, model, airC = 33.0)
            val shiftId = requireNotNull(model.openShift).shift.id
            // The lock-screen timer and the real AlarmManager-backed break-due alarm follow the shift.
            compose.waitUntil(10_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_ONGOING } }
            val ongoing = manager.activeNotifications.single { it.id == ShiftNotifications.ID_ONGOING }.notification
            assertTrue(ongoing.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
            val scheduler = AlertScheduler(context)
            compose.waitUntil(10_000) { scheduler.armed("local").any { it.kind == AlarmKind.BREAK_DUE.name && it.shiftId == shiftId } }
            val firstDue = scheduler.armed("local").first { it.kind == AlarmKind.BREAK_DUE.name }.atMillis
            // Heavy work (Hot → Very hot for you) moves the next break earlier and replaces the alarm.
            scenario.onActivity { model.setHeavyWork(true) }
            compose.waitUntil(10_000) { scheduler.armed("local").firstOrNull { it.kind == AlarmKind.BREAK_DUE.name }?.atMillis?.let { it < firstDue } == true }
            val movedAlarms = scheduler.armed("local").filter { it.kind == AlarmKind.BREAK_DUE.name }
            assertEquals(setOf(shiftId), movedAlarms.map { it.shiftId }.toSet())
            assertEquals(setOf(firstDue - 15 * 60_000L), movedAlarms.map { it.atMillis }.toSet())
            // End shift from Today asks first: the timer goes, the break alarm goes, the next start nudge stays.
            compose.onNodeWithTag("shift_end").performScrollTo().performClick()
            compose.onNodeWithTag("shift_end_confirm").performClick()
            compose.waitUntil(10_000) { model.openShift == null }
            compose.waitUntil(10_000) { manager.activeNotifications.none { it.id == ShiftNotifications.ID_ONGOING } }
            compose.waitUntil(10_000) { scheduler.armed("local").map { it.kind }.toSet() == setOf(AlarmKind.START_NUDGE.name) }
            compose.onNodeWithTag("summary_done").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            // The second Start shift goes straight to the sheet: the question was asked once.
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            compose.onNodeWithTag("nav_today").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            assertNotNull(runBlocking { database.dao().getSession("local", shiftId) })
        } finally { scenario.close() }
    }

    /**
     * M3 Task 7: the first Start shift asks about break alerts once (alerts-intro, titled "Break alerts"). "Not now" goes
     * on to Start shift with the question taken off the back stack, turns the alerts switch off and says where to turn it on;
     * the next Start shift opens the shift screen at once. The other journeys start as an owner that has already answered
     * (withLocalOwnerRestored sets AlertCoordinator.askedKey("local")), so this one removes the key to see the question.
     * M3 fix wave: the question is the owner's, not the phone's (another owner on the phone is asked too), and the hot-day
     * nudge's Start shift deep link asks it as well; Back from a deep link returns to Today.
     */
    @Test
    fun theFirstStartShiftAsksAboutAlertsOnceAndNotNowGoesOnToTheShift() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val alertSession = context.getSharedPreferences(AlertCoordinator.PREFS, Context.MODE_PRIVATE)
        alertSession.edit().remove(AlertCoordinator.askedKey("local")).commit()
        // Another owner on this phone has answered already; the local owner has not, so it is still asked.
        val other = "another-owner-${UUID.randomUUID().toString().take(8)}"
        alertSession.edit().putBoolean(AlertCoordinator.askedKey(other), true).commit()
        // Opened from the hot-day nudge's Start shift: the deep link asks first too.
        val nudge = Intent(context, MainActivity::class.java).setAction(heatshield.background.DeepLinks.ACTION_START_SHIFT)
        val scenario = ActivityScenario.launch<MainActivity>(nudge)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Break alerts"), model)
            compose.waitUntil(10_000) { model.shiftLoaded }
            assertFalse(model.alertStatus.asked)
            assertFalse(model.alertsAsked())
            // Back from the deep link's question returns to Today; Today's own Start shift asks it again.
            androidx.test.espresso.Espresso.pressBack()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Break alerts"), model)
            compose.onNodeWithTag("alerts_intro_card").assertExists()
            compose.onNodeWithTag("alerts_not_now").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            // The refusal is answered: alerts stay off, and the message bar says where to turn them on.
            awaitNode(hasTestTag("app_message") and hasAnyDescendant(hasText("Alerts stay off. Your timer still runs on Today; turn alerts on in Profile any time.")), model)
            assertTrue(model.alertStatus.asked)
            assertFalse(model.preferences.reminders)
            assertFalse(runBlocking { dao.getPreferences("local") }!!.reminders)
            // Back returns to Today, not to the question.
            androidx.test.espresso.Espresso.pressBack()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            compose.onNodeWithTag("shift_start").performScrollTo().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Start shift"), model)
            assertNull(runBlocking { dao.getOpenShift("local") })
            assertTrue(alertSession.getBoolean(AlertCoordinator.askedKey("local"), false))
        } finally {
            scenario.close()
            alertSession.edit().remove(AlertCoordinator.askedKey(other)).commit()
        }
    }

    /**
     * M3 Task 7 fix: Profile › Alerts is where a gardener allows notifications mid-shift, and Android's permission dialog
     * only pauses the activity: no ON_STOP/ON_START, so the app's own return-to-view reconcile does not run. While
     * notifications were blocked the plan armed no break alarm and posted no lock-screen timer, so the rows' refresh
     * (which the dialog's result and every resume call) must reconcile, and the grant brings both back at once rather
     * than at the next forecast refresh. The test cannot revoke the permission without killing its own process, so it
     * takes the running shift's alarms and timer away (what a blocked phone has) and pauses and resumes the activity the
     * way the dialog does (ActivityScenario pauses it under a transparent activity).
     */
    @Test
    fun aResumeOnProfileAlertsReArmsTheBreakAlarmAndTheTimerAtOnce() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = ShiftNotifications(context)
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val manager = context.getSystemService(NotificationManager::class.java)
        val scheduler = AlertScheduler(context)
        val dao = database.dao()
        // Smoko and lunch twelve hours away, so no scheduled break covers the first due time and it is armed as an alarm.
        val away = (ShiftRules.localTime(System.currentTimeMillis()).toLocalTime().toSecondOfDay() / 60 + 720) % 1440
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true, reminders = true,
                smokoMinute = away, lunchMinute = away))
            dao.deletePlan("local")
            dao.saveForecast(todayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            settleAndSeed(database, model, airC = 33.0)
            val start = ShiftRules.localTime(System.currentTimeMillis())
            scenario.onActivity {
                // Heavy work makes Hot "Very hot for you": a break falls due 30 minutes in.
                model.startShift(StartShiftInput(model.context.site, "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(10_000) { !model.busy && model.shiftState is ShiftUiState.On }
            compose.waitUntil(10_000) { scheduler.armed("local").any { it.kind == AlarmKind.BREAK_DUE.name } }
            compose.waitUntil(10_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_ONGOING } }

            compose.onNodeWithTag("nav_profile").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Profile"), model)
            compose.onNodeWithTag("alerts_row_notifications").performScrollTo().assertTextEquals("Notifications: allowed")
            compose.waitForIdle()
            // What a phone with notifications blocked has: no break-due alarm and no timer. Under the owner lock, so a
            // reconcile already running finishes first and cannot re-arm behind the test's back.
            runBlocking { AlertCoordinator.ownerLock.withLock { scheduler.cancelAll("local"); notifications.cancel(ShiftNotifications.ID_ONGOING) } }
            compose.waitUntil(10_000) { manager.activeNotifications.none { it.id == ShiftNotifications.ID_ONGOING } }
            assertTrue(scheduler.armed("local").isEmpty())

            // The permission dialog: the activity pauses under a transparent activity and resumes; it never stops.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { scheduler.armed("local").any { it.kind == AlarmKind.BREAK_DUE.name } }
            compose.waitUntil(10_000) { manager.activeNotifications.any { it.id == ShiftNotifications.ID_ONGOING } }
        } finally {
            scenario.close()
        }
    }

    /**
     * Profile reads the date from the app's own ticking clock. "Back from leave" set to end at tonight's midnight shows
     * on, with today as its last day; once the app's clock passes midnight it shows off with no settings write, and
     * turning it on then counts seven days from the new day. Only the app's clock moves; the phone's stays as it is.
     */
    @Test
    fun profileAcclimatisationFollowsTheAppClockPastMidnight() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        val today = LocalDate.now(MelbourneZone)
        val midnight = today.plusDays(1).atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true, acclimatisingUntil = midnight))
            dao.deletePlan("local")
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            // No refresh or forecast write lands later and puts the app's clock back to the phone's.
            settleAndSeed(database, model, airC = 26.0)
            compose.onNodeWithTag("nav_profile").performClick()
            awaitNode(hasTestTag("app_title") and hasText("Profile"), model)
            val acclimatising = hasText("New to outdoor work, or back from leave") and hasClickAction()
            compose.onNode(acclimatising).performScrollTo().assertIsOn()
                .assertTextContains("until the end of ${Formats.dayDate(today)}", substring = true)
            // The ticker stops, so the app's clock stays 30 s past midnight while the screen is read.
            scenario.onActivity { model.setVisible(false); setModelNow(model, midnight + 30_000L) }
            compose.onNode(acclimatising).performScrollTo().assertIsOff()
            assertEquals(midnight, runBlocking { dao.getPreferences("local") }?.acclimatisingUntil)
            compose.onNode(acclimatising).performClick()
            val weekFromTomorrow = heatshield.ui.SetupRules.acclimatisingUntil(today.plusDays(1))
            compose.waitUntil(10_000) { runBlocking { dao.getPreferences("local") }?.acclimatisingUntil == weekFromTomorrow }
        } finally {
            scenario.close()
        }
    }

    /**
     * First aid opened while End shift is still saving stays open: the save finishes, the shift is in the log, and its
     * summary does not cover first aid. The test holds Room's write lock, so End shift's save waits until first aid is open.
     */
    @Test
    fun firstAidOpenedWhileEndShiftSavesIsNotCoveredByTheSummary() = withLocalOwnerRestored { database ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = database.dao()
        runBlocking {
            dao.savePreferences(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true))
            dao.deletePlan("local")
            dao.saveForecast(aroundTodayForecast("Birrarung Marr"))
        }
        context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE).edit().putBoolean("local", true).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val model = modelOf(scenario)
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            val start = ShiftRules.localTime(System.currentTimeMillis() - 30 * 60_000L)
            scenario.onActivity {
                model.startShift(StartShiftInput("Birrarung Marr", "Push mowing", heavyWork = true, heavyClothing = false,
                    startedAt = start, plannedEnd = start.plusHours(4))) {}
            }
            compose.waitUntil(20_000) { model.openShift != null && !model.busy }
            val shiftId = requireNotNull(model.openShift).shift.id
            compose.onNodeWithTag("shift_end").performScrollTo().performClick()
            val holding = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val holder = Thread {
                val db = database.openHelper.writableDatabase
                db.beginTransactionNonExclusive()   // BEGIN IMMEDIATE: Room's next write waits for this one
                try { holding.countDown(); release.await(30, TimeUnit.SECONDS) } finally { db.endTransaction() }
            }.apply { start() }
            try {
                assertTrue("The test could not hold the write lock", holding.await(10, TimeUnit.SECONDS))
                compose.onNodeWithTag("shift_end_confirm").performClick()
                assertTrue("End shift is saving", model.busy)
                compose.onNode(hasText("First aid") and hasClickAction()).performClick()
                awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), model)
            } finally {
                release.countDown(); holder.join(10_000)
            }
            compose.waitUntil(20_000) { !model.busy && model.openShift == null }
            compose.waitForIdle()
            awaitNode(hasTestTag("app_title") and hasText("Heat first aid"), model)
            compose.onAllNodes(hasTestTag("app_title") and hasText("Shift summary")).assertCountEquals(0)
            assertNotNull(runBlocking { dao.getSession("local", shiftId) }?.endedAt)
        } finally {
            scenario.close()
        }
    }

    /**
     * Runs [block] from a signed-out phone that has already answered the alerts intro, then puts back the local owner,
     * cache rows and switches it touched.
     */
    private fun withLocalOwnerRestored(block: (HeatShieldDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeCloudUser = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?.let { FirebaseAuth.getInstance(it).currentUser }
        assumeTrue("This local integration test preserves any signed-in cloud account.", activeCloudUser == null)
        val device = context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE)
        val previousDeviceValues = listOf("local").associateWith { device.all[it] }
        val alertSession = context.getSharedPreferences(AlertCoordinator.PREFS, Context.MODE_PRIVATE)
        val previousAlertOwner = alertSession.getString(AlertCoordinator.KEY_OWNER, null)
        val previousAlertValues = listOf(AlertCoordinator.askedKey("local")).associateWith { alertSession.all[it] }
        val database = HeatShieldDatabase.get(context)
        val dao = database.dao()
        val previousPreferences = runBlocking { dao.getPreferences("local") }
        val previousPlan = runBlocking { dao.getPlan("local") }
        val previousIds = runBlocking { dao.observeSessions("local").first().map { it.id }.toSet() }
        assumeTrue("An existing user's running shift is preserved.", runBlocking { dao.getOpenShift("local") } == null)
        val previousForecasts = runBlocking { WorkSite.defaults.associate { it.name to dao.getForecast(it.name) } }
        try {
            device.edit().putBoolean("local", false).commit()
            // The alerts intro is asked once per owner (askedKey), and a fresh install has not asked it yet. These journeys
            // start shifts as a local owner that has already answered, so Start shift opens the shift screen; a journey about
            // the intro itself removes the key inside its block.
            alertSession.edit().putBoolean(AlertCoordinator.askedKey("local"), true).commit()
            block(database)
        } finally {
            runBlocking {
                database.withTransaction {
                    dao.observeSessions("local").first().filter { it.id !in previousIds }.forEach { dao.deleteSession("local", it.id) }
                    if (previousPreferences != null) dao.savePreferences(previousPreferences) else deleteLocalPreferences(database)
                    if (previousPlan != null) dao.savePlan(previousPlan) else dao.deletePlan("local")
                    previousForecasts.forEach { (site, cache) ->
                        if (cache != null) dao.saveForecast(cache) else dao.deleteForecast(site)
                    }
                }
            }
            restore(device, previousDeviceValues)
            restore(alertSession, previousAlertValues)
            runBlocking { AlertCoordinator(context).activateOwner(previousAlertOwner); AlertCoordinator(context).reconcile() }
            val originalSiteName = previousPlan?.context?.takeIf { it.date == LocalDate.now(MelbourneZone) }?.site
                ?: previousPreferences?.site ?: "Birrarung Marr"
            val originalSite = runBlocking { dao.getSite(originalSiteName) } ?: WorkSite.byName(originalSiteName)
            if (previousDeviceValues["local"] == true && originalSite != null) ForecastWorker.enqueue(context, originalSite)
            else ForecastWorker.cancel(context)
        }
    }

    private fun deleteLocalPreferences(database: HeatShieldDatabase) =
        database.openHelper.writableDatabase.execSQL("DELETE FROM user_preferences WHERE ownerId = ?", arrayOf("local"))

    /** Waits until the top bar no longer reads [title], so the tap before it has opened another screen. */
    private fun awaitTitleOtherThan(title: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("app_title") and hasText(title)).fetchSemanticsNodes().isEmpty()
    }

    /** A fresh, valid forecast for today, so Today has a peak before the network answers. */
    private fun todayForecast(site: String): ForecastCache {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(MelbourneZone)
        val hours = (0..23).map { hour ->
            ForecastHour(today.atTime(hour, 0).toString(), temperature = 18.0 + minOf(hour, 14) * 0.5,
                humidity = 50.0, uv = 2.0, windSpeed = 3.0, uvClearSky = 2.5)
        }
        return ForecastCache(site = site, fetchedAt = now, hoursJson = Gson().toJson(hours), lastAttempt = now,
            outcome = "success")
    }

    /**
     * A fresh forecast from yesterday to tomorrow, 33° every hour, as the app's refresh keeps it (past_days=1): a shift
     * that started yesterday evening has its hours, and so does a test that runs on past midnight.
     */
    private fun aroundTodayForecast(site: String): ForecastCache {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(MelbourneZone)
        val hours = listOf(today.minusDays(1), today, today.plusDays(1)).flatMap { day ->
            (0..23).map { hour -> ForecastHour(day.atTime(hour, 0).toString(), temperature = 33.0, humidity = 10.0, uv = 2.0, windSpeed = 8.0) }
        }
        return ForecastCache(site = site, fetchedAt = now, hoursJson = Gson().toJson(hours), lastAttempt = now, outcome = "success")
    }

    /**
     * Lets the real foreground request settle and stops background refresh, then publishes a deterministic forecast for
     * Today's park with [airC] in every hour (10% humidity, 8 m/s wind), from yesterday to tomorrow as the app's refresh
     * keeps it (past_days=1): a break that falls due after midnight, in a run late in the evening, still has its hour.
     */
    private fun settleAndSeed(database: HeatShieldDatabase, model: HeatShieldViewModel, airC: Double) {
        compose.waitUntil(45_000) { !model.refreshing }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManager.getInstance(context).cancelUniqueWork("heatshield_forecast").result.get(10, TimeUnit.SECONDS)
        val cache = todayForecast(model.context.site)
        val date = LocalDate.now(MelbourneZone)
        val hours = listOf(date.minusDays(1), date, date.plusDays(1)).flatMap { day ->
            (0..23).map { ForecastHour(day.atTime(it, 0).toString(), airC, 10.0, 6.0, 8.0, 6.0) }
        }
        runBlocking { database.dao().saveForecast(cache.copy(hoursJson = Gson().toJson(hours))) }
        compose.waitUntil(10_000) { model.todayHeat.peak != null && model.todayHeat.hours.all { it.airC == airC } }
    }

    /**
     * Sends a notification action's PendingIntent the way the shade does. From Android 14 a sender must opt in before its
     * PendingIntent may start an activity (the test process, unlike System UI, has no standing grant).
     */
    private fun sendAsTheShadeDoes(action: PendingIntent) {
        if (Build.VERSION.SDK_INT < 34) return action.send()
        val mode = if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        action.send(ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle())
    }

    /** The MainActivity in the resumed stage, if any: one a PendingIntent started, which no ActivityScenario holds. */
    private fun resumedMainActivity(instrumentation: android.app.Instrumentation): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
        }
        return found
    }

    private fun modelOf(scenario: ActivityScenario<MainActivity>): HeatShieldViewModel {
        lateinit var model: HeatShieldViewModel
        scenario.onActivity { model = ViewModelProvider(it)[HeatShieldViewModel::class.java] }
        return model
    }

    private fun awaitNode(matcher: SemanticsMatcher, model: HeatShieldViewModel) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
            compose.onNode(matcher).assertExists()
        } catch (failure: Throwable) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8) }
                .getOrElse { "Unable to read the current semantics tree: ${it.message}" }
            throw AssertionError("UI did not reach $matcher. owner=${model.ownerId}, shift=${model.openShift?.shift?.id}, " +
                "site=${model.context.site}, loading=${model.loading}, busy=${model.busy}, setup=${model.needsSetup}\n$tree", failure)
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        preferences.edit().apply {
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                    is Int -> putInt(key, value)
                    else -> remove(key)
                }
            }
        }.commit()
    }
}
