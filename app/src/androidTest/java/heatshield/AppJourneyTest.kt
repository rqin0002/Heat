package heatshield

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import heatshield.background.ForecastWorker
import heatshield.background.ReminderScheduler
import heatshield.data.HeatShieldDatabase
import heatshield.data.WorkSite
import heatshield.data.WorkSession
import heatshield.ui.HeatShieldViewModel
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real activity, ViewModel, timer preferences and Room without fixtures in the UI. */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun localResponseAndMeasuredBreakSurviveActivityRecreationAndRelaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeCloudUser = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?.let { FirebaseAuth.getInstance(it).currentUser }
        assumeTrue("This local integration test preserves any signed-in cloud account.", activeCloudUser == null)
        val device = context.getSharedPreferences("heatshield_session", Context.MODE_PRIVATE)
        assumeTrue("An existing user's running timer is preserved.", device.getLong("break_start_local", 0) == 0L)
        val keys = listOf("local", "break_start_local", "break_session_local", "break_boot_local")
        val previousDeviceValues = keys.associateWith { device.all[it] }
        val reminderState = context.getSharedPreferences("reminder_session", Context.MODE_PRIVATE)
        val previousReminderOwner = reminderState.getString("owner", null)
        val database = HeatShieldDatabase.get(context)
        val dao = database.dao()
        val previousPreferences = runBlocking { dao.getPreferences("local") }
        val previousPlan = runBlocking { dao.getPlan("local") }
        val title = "Integration pruning ${UUID.randomUUID().toString().take(8)}"
        var createdId: Int? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            device.edit().putBoolean("local", false).commit()
            runBlocking { dao.deletePlan("local") }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.onNodeWithText("Continue on this device").performScrollTo().performClick()
            var model = modelOf(scenario)
            compose.waitUntil(10_000) { model.ownerId == "local" && !model.loading }
            val preferencesSaved = AtomicBoolean(false)
            scenario.onActivity {
                model.savePreferences("Birrarung Marr", "Light effort", "Standard workwear", false) {
                    preferencesSaved.set(true)
                }
            }
            compose.waitUntil(10_000) { preferencesSaved.get() && !model.needsOnboarding && !model.busy }
            compose.waitForIdle()
            val recordSaved = AtomicBoolean(false)
            scenario.onActivity {
                model.saveRecord(WorkSession(ownerId = "local", title = title, site = "Birrarung Marr",
                    date = LocalDate.now(HeatShieldViewModel.MELBOURNE).format(HeatShieldViewModel.RECORD_DATE),
                    outdoorMinutes = 60, breakMinutes = 5, status = "Reviewed")) { recordSaved.set(true) }
            }
            compose.waitUntil(10_000) { recordSaved.get() && model.sessions.any { it.title == title } }
            val recordId = model.sessions.single { it.title == title }.id
            createdId = recordId
            compose.waitUntil(10_000) { model.activeSession?.id == recordId }
            compose.onNodeWithTag("nav_today").assertIsEnabled().performClick()
            awaitNode(hasTestTag("app_title") and hasText("Today"), model)
            awaitNode(hasText(title, substring = true), model)

            val responseSaved = AtomicBoolean(false)
            scenario.onActivity { model.recordResponse(false, "Cannot pause yet") { responseSaved.set(true) } }
            compose.waitUntil(10_000) { responseSaved.get() }
            val pending = runBlocking { requireNotNull(dao.getSession("local", recordId)) }
            assertEquals("Needs review", pending.status)
            assertEquals(5, pending.breakMinutes)
            assertTrue(pending.checkInNote.orEmpty().contains("Cannot pause yet"))

            scenario.onActivity { model.startBreak() }
            val timerStart = model.breakStartedAt
            assertTrue(timerStart > 0)
            scenario.onActivity { model.startBreak() }
            assertEquals("Starting twice must preserve the original timer", timerStart, model.breakStartedAt)
            compose.onNodeWithText("Check in now").performScrollTo().performClick()
            compose.onNodeWithText("Break in progress").assertExists()
            scenario.recreate()
            model = modelOf(scenario)
            assertEquals(timerStart, model.breakStartedAt)
            compose.onNodeWithText("Break in progress").assertExists()

            // Real elapsed time; never seed a made-up elapsed minute into the record.
            compose.waitUntil(5_000) { SystemClock.elapsedRealtime() - timerStart >= 1_000 }
            val previousModel = model
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            model = modelOf(scenario)
            assertNotSame(previousModel, model)
            compose.waitUntil(10_000) { !model.loading && model.activeSession?.id == recordId }
            assertEquals(timerStart, model.breakStartedAt)
            assertEquals(recordId, model.breakSessionId)
            compose.onNodeWithText("Check in now").performScrollTo().performClick()
            val secondsBeforeFinish = (SystemClock.elapsedRealtime() - timerStart) / 1_000
            compose.onNodeWithText("Finish break").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                !model.busy && model.breakStartedAt == 0L && model.sessions.find { it.id == recordId }?.status == "Reviewed"
            }
            val completed = runBlocking { requireNotNull(dao.getSession("local", recordId)) }
            val measuredSeconds = Regex("measured for (\\d+) seconds").find(completed.checkInNote.orEmpty())
                ?.groupValues?.get(1)?.toLong()
            val secondsAfterFinish = (SystemClock.elapsedRealtime() - timerStart) / 1_000
            assertTrue("The duration must reflect actual elapsed time.",
                measuredSeconds != null && measuredSeconds in secondsBeforeFinish..secondsAfterFinish)
            assertEquals(5 + (requireNotNull(measuredSeconds) / 60).toInt(), completed.breakMinutes)
            assertEquals(60, completed.outdoorMinutes)

            val duplicateCompleted = AtomicBoolean(false)
            scenario.onActivity { model.recordResponse(true, "", { duplicateCompleted.set(true) }) }
            compose.waitUntil(10_000) { !model.busy && model.message != null }
            assertTrue("A completed break cannot be recorded twice", !duplicateCompleted.get())
            assertEquals(completed, runBlocking { dao.getSession("local", recordId) })
            scenario.onActivity { model.dismissMessage() }

            scenario.recreate()
            model = modelOf(scenario)
            compose.waitUntil(10_000) { model.sessions.any { it.id == recordId && it == completed } }
            compose.onNodeWithTag("nav_records").assertIsEnabled().performClick()
            awaitNode(hasText(title), model)
            assertEquals(completed, runBlocking { dao.getSession("local", recordId) })

            scenario.onActivity { model.signOut() }
            compose.waitUntil(10_000) { model.ownerId == null && model.sessions.isEmpty() }
            compose.onNodeWithText("Continue on this device").assertExists()
            assertEquals(0L, model.breakStartedAt)
            assertEquals(completed, runBlocking { dao.getSession("local", recordId) })
            compose.onNodeWithText("Continue on this device").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.loading && model.sessions.any { it.id == recordId } }
            assertEquals(completed, model.sessions.single { it.id == recordId })
        } finally {
            scenario?.close()
            runBlocking {
                database.withTransaction {
                    val testRecordId = createdId ?: dao.observeSessions("local").first()
                        .singleOrNull { it.title == title }?.id
                    testRecordId?.let { dao.deleteSession("local", it) }
                    if (previousPreferences != null) dao.savePreferences(previousPreferences)
                    else database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM user_preferences WHERE ownerId = ?", arrayOf("local"))
                    if (previousPlan != null) dao.savePlan(previousPlan) else dao.deletePlan("local")
                }
            }
            restore(device, previousDeviceValues)
            ReminderScheduler(context).activateOwner(previousReminderOwner)
            runBlocking { ReminderScheduler(context).restoreForActiveOwner() }
            val originalSiteName = previousPlan?.context?.takeIf {
                it.date == LocalDate.now(HeatShieldViewModel.MELBOURNE)
            }?.site ?: previousPreferences?.site ?: "Birrarung Marr"
            val originalSite = runBlocking { dao.getSite(originalSiteName) } ?: WorkSite.byName(originalSiteName)
            if (previousDeviceValues["local"] == true && originalSite != null) ForecastWorker.enqueue(context, originalSite)
            else ForecastWorker.cancel(context)
        }
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
            throw AssertionError("UI did not reach $matcher. owner=${model.ownerId}, active=${model.activeSession?.id}, " +
                "site=${model.context.site}, loading=${model.loading}, busy=${model.busy}, onboarding=${model.needsOnboarding}\n$tree", failure)
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
