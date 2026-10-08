package heatshield

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import heatshield.background.AlarmSink
import heatshield.background.AlertAction
import heatshield.background.AlertCoordinator
import heatshield.background.AlertScheduler
import heatshield.background.AutoEndedShift
import heatshield.background.ShiftNotifications
import heatshield.data.AlertReason
import heatshield.data.ForecastCache
import heatshield.data.ForecastHour
import heatshield.data.HeatShieldDatabase
import heatshield.data.MelbourneZone
import heatshield.data.ShiftClock
import heatshield.data.ShiftEventType
import heatshield.data.ShiftRepository
import heatshield.data.StartShiftInput
import heatshield.data.UserPreferences
import heatshield.domain.AlarmKind
import heatshield.domain.BreakAlertDecision
import heatshield.domain.QuietReason
import heatshield.domain.ShiftRules
import heatshield.ui.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** reconcile() from Room: alarms follow the shift, the ongoing timer follows the shift, a reboot re-arms from wall time, an owner switch or sign-out mid-read leaves the old owner nothing (spec §10 M3). */
@RunWith(AndroidJUnit4::class)
class AlertCoordinatorTest {
    private class FakeShiftClock(var wall: Long, var elapsed: Long, var boot: Int) : ShiftClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        override fun bootCount() = boot
        fun advanceMinutes(minutes: Long) { wall += minutes * 60_000L; elapsed += minutes * 60_000L }
        fun rebootAfterMinutes(minutes: Long) { wall += minutes * 60_000L; elapsed = 5_000L; boot += 1 }
    }

    /** What AlertScheduler asks of AlarmManager, recorded: the fake clock's elapsed triggers never reach the AVD's real one (AlertSchedulerTest covers the real shapes). */
    private class RecordingAlarmSink(var exact: Boolean = true) : AlarmSink {
        val armed = mutableMapOf<PendingIntent, Long>()
        override fun exactAllowed() = exact
        override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) { armed[operation] = triggerAtMillis }
        override fun setWindow(type: Int, windowStartMillis: Long, windowLengthMillis: Long, operation: PendingIntent) { armed[operation] = windowStartMillis }
        override fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) { armed[operation] = triggerAtMillis }
        override fun cancel(operation: PendingIntent) { armed.remove(operation) }
    }

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var database: HeatShieldDatabase
    private lateinit var clock: FakeShiftClock
    private val sink = RecordingAlarmSink()
    private lateinit var scheduler: AlertScheduler
    private lateinit var notifications: ShiftNotifications
    private lateinit var coordinator: AlertCoordinator
    private lateinit var shifts: ShiftRepository
    private val databaseName = "alert-coordinator-test-${UUID.randomUUID()}.db"
    private val owner = "alerts-${UUID.randomUUID().toString().take(8)}"
    private val today: LocalDate = LocalDate.now(MelbourneZone)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = today.atTime(hour, minute)
    private fun i(time: LocalDateTime): Instant = ShiftRules.instant(time)
    /** The phone's own alert prefs this test touches (owner, dismissed shift), put back afterwards. The asked flag is per owner, and these owners are throw-away. */
    private val savedKeys = listOf(AlertCoordinator.KEY_OWNER, AlertCoordinator.KEY_DISMISSED)
    private var saved: Map<String, Any?> = emptyMap()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** Where background refresh was pointed, in order; null is "stopped". Recorded so the phone's real forecast work never moves. */
    private val refreshed = java.util.Collections.synchronizedList(mutableListOf<heatshield.data.WorkSite?>())

    /** Every coordinator this test builds, the rebooted and the racing ones included: the fake clock, the recording sink, the recorded background refresh and a sign-in check that trusts the throw-away owner. */
    private fun coordinator(isSignedIn: (String) -> Boolean = { it == owner }) =
        AlertCoordinator(context, database, clock, scheduler, notifications, isSignedIn, backgroundRefresh = { refreshed += it })

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = context.getSharedPreferences(AlertCoordinator.PREFS, Context.MODE_PRIVATE)
        saved = savedKeys.associateWith { prefs.all[it] }
        // Keep the real owner's alarm ledger untouched while activating the throw-away owner.
        prefs.edit().remove(AlertCoordinator.KEY_OWNER).remove(AlertCoordinator.KEY_DISMISSED).commit()
        database = HeatShieldDatabase.builder(context, databaseName).build()
        clock = FakeShiftClock(wall = i(t(7, 2)).toEpochMilli(), elapsed = 3_600_000L, boot = 12)
        scheduler = AlertScheduler(context, sink); notifications = ShiftNotifications(context)
        coordinator = coordinator()
        shifts = ShiftRepository(database, clock)
        runBlocking {
            database.dao().savePreferences(UserPreferences(ownerId = owner, site = "Royal Park", task = "Push mowing", effort = "Heavy effort", reminders = true, setupDone = true))
            seedForecast(airC = 33.0)
            coordinator.activateOwner(owner)                      // suspend: it takes the owner boundary
        }
    }

    @After
    fun close() {
        scheduler.cancelAll(owner)
        listOf(ShiftNotifications.ID_ONGOING, ShiftNotifications.ID_BREAK_DUE, ShiftNotifications.ID_NUDGE, ShiftNotifications.ID_OVERRUN, ShiftNotifications.ID_AUTO_ENDED).forEach(notifications::cancel)
        // The phone's own values go back exactly (a key that was absent stays absent), then the production coordinator re-arms whatever the real owner has.
        prefs.edit().apply {
            saved.forEach { (key, value) -> when (value) { null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is String -> putString(key, value); else -> remove(key) } }
            prefs.all.keys.filter { it.endsWith(":$owner") }.forEach { remove(it) }
            remove(AlertCoordinator.askedKey(owner)); remove(AlertCoordinator.askedKey("someone-else"))
        }.commit()
        runBlocking { AlertCoordinator(context).reconcile() }
        database.close(); context.deleteDatabase(databaseName)
    }

    /**
     * Today's hours at [airC] (39°, Extreme, from the hour [extremeFrom] when given), fetched [ageMinutes] ago: 5 is FRESH; 7 × 60 is
     * STALE (Models.freshness), which shiftTimingHours withholds. The heat number is max(air, feels like), so dry 33° air is Hot and 39° Extreme.
     */
    private suspend fun seedForecast(airC: Double, ageMinutes: Long = 5, extremeFrom: Int? = null, day: LocalDate = today) {
        val hours = (0..23).map { h -> ForecastHour(day.atTime(h, 0).toString(), if (extremeFrom != null && h >= extremeFrom) 39.0 else airC, 10.0, 6.0, windSpeed = 8.0, uvClearSky = 6.0) }
        val fetched = clock.wall - ageMinutes * 60_000L
        database.dao().saveForecast(ForecastCache("Royal Park", fetched, Gson().toJson(hours), fetched, "success"))
    }
    private fun input(heavy: Boolean) = StartShiftInput("Royal Park", "Push mowing", heavyWork = heavy, heavyClothing = false, startedAt = t(7, 2), plannedEnd = t(15))
    private fun armedKinds() = scheduler.armed(owner).map { it.kind }.toSet()
    private fun breakDueAt() = scheduler.armed(owner).firstOrNull { it.kind == AlarmKind.BREAK_DUE.name }?.atMillis?.let(Instant::ofEpochMilli)
    private fun pendingExists(kind: AlarmKind, shiftId: Int): Boolean = scheduler.armed(owner).filter { it.kind == kind.name }.any { record ->
        PendingIntent.getBroadcast(context, record.code, AlertScheduler.fireIntent(context, owner, kind, shiftId), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    }
    /**
     * The shade as Android holds it. NotificationManager posts and cancels on the system's own thread, so a read straight
     * after a reconcile can see the state before it: these wait (up to 5 s) for what the assertion expects, and the
     * assertion then fails on what was last seen.
     */
    private fun active(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }?.notification
    private fun settle(done: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + 5_000L
        while (!done() && SystemClock.uptimeMillis() < until) SystemClock.sleep(25)
        return done()
    }
    private fun shows(id: Int) = settle { active(id) != null }
    private fun gone(id: Int) = settle { active(id) == null }
    private fun shown(id: Int, expected: (android.app.Notification) -> Boolean = { true }): android.app.Notification {
        settle { active(id)?.let(expected) == true }
        return requireNotNull(active(id)) { "notification $id is not showing" }
    }
    private fun title(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
    private fun text(n: android.app.Notification) = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
    private suspend fun events(shiftId: Int) = database.dao().getEvents(shiftId)

    @Test
    fun reconcileTwiceLeavesOneSetOfAlarmsAndOneOngoingNotificationAndEndShiftClearsThem() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.reconcile(); coordinator.reconcile()
        assertEquals(setOf(AlarmKind.AUTO_END.name, AlarmKind.STILL_ON_SHIFT.name, AlarmKind.BREAK_DUE.name, AlarmKind.REFRESH.name), armedKinds())
        assertEquals(4, sink.armed.size)                        // four operations on the sink: the second reconcile replaced them, never doubled them
        assertEquals(i(t(8)).toEpochMilli(), scheduler.armed(owner).single { it.kind == AlarmKind.REFRESH.name }.atMillis)   // the next hour's words
        assertTrue(pendingExists(AlarmKind.BREAK_DUE, id))
        assertEquals(i(t(7, 47)), breakDueAt())                    // Hot (33°): 45 minutes from 7:02
        assertTrue(shows(ShiftNotifications.ID_ONGOING))
        val ongoing = shown(ShiftNotifications.ID_ONGOING)
        assertTrue(ongoing.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(i(t(7, 2)).toEpochMilli(), ongoing.`when`)   // wall now (7:02) less 0 worked minutes
        clock.advanceMinutes(20)                                  // endShift rejects an end that is not after the start (INVALID_TIMES): 7:22 ends a 7:02 shift
        shifts.endShift(owner, emptyList())
        coordinator.reconcile()
        assertEquals(setOf(AlarmKind.START_NUDGE.name), armedKinds())   // off shift, alerts on: tomorrow's nudge only
        assertEquals(1, sink.armed.size)
        assertFalse(pendingExists(AlarmKind.BREAK_DUE, id))
        assertTrue(gone(ShiftNotifications.ID_ONGOING))
    }

    @Test
    fun heavyWorkMovesTheBreakDueAlarmEarlier() = runBlocking {
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.reconcile()
        assertEquals(i(t(7, 47)), breakDueAt())
        shifts.setHeavyWork(owner, true)                          // Hot → Very hot for you: 30 minutes
        coordinator.reconcile()
        assertEquals(i(t(7, 32)), breakDueAt())
        assertTrue(pendingExists(AlarmKind.BREAK_DUE, id))
        assertEquals(1, scheduler.armed(owner).count { it.kind == AlarmKind.BREAK_DUE.name })   // exact (the sink allows it)
        sink.exact = false                                        // precise timing denied: the next reconcile arms the de-duplicated pair instead
        coordinator.reconcile()
        assertEquals(2, scheduler.armed(owner).count { it.kind == AlarmKind.BREAK_DUE.name })
    }

    @Test
    fun rebootRecomputesFromWallTimeAndReArms() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.reconcile()
        clock.advanceMinutes(20)
        // A reboot: the system drops every alarm and notification; the boot count ticks; the elapsed clock restarts.
        scheduler.cancelAll(owner); notifications.cancel(ShiftNotifications.ID_ONGOING)
        clock.rebootAfterMinutes(5)
        val afterBoot = coordinator()                             // the same factory: the fake sign-in check and the recording sink
        afterBoot.reconcile()
        assertEquals(i(t(7, 27)), ShiftRepository(database, clock).now(requireNotNull(database.dao().getOpenShift(owner))))
        assertEquals(i(t(7, 47)), breakDueAt())                    // the due time is on the shift's timeline, not the boot's
        assertTrue(pendingExists(AlarmKind.BREAK_DUE, id))
        assertTrue(shows(ShiftNotifications.ID_ONGOING))
    }

    @Test
    fun aRebootPastTheAutomaticEndEndsTheShiftBeforeAnythingIsPlanned() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.reconcile()
        assertEquals(setOf(AlarmKind.AUTO_END.name, AlarmKind.STILL_ON_SHIFT.name, AlarmKind.BREAK_DUE.name, AlarmKind.REFRESH.name), armedKinds())
        // Off until 17:02, past the 17:00 automatic end: the AUTO_END alarm died with the boot and its time is in the past now.
        scheduler.cancelAll(owner); notifications.cancel(ShiftNotifications.ID_ONGOING)
        clock.rebootAfterMinutes(10 * 60)
        // The screen, when open, is told too (the ViewModel shows its in-app notice from this).
        val announced = async(start = CoroutineStart.UNDISPATCHED) { AlertCoordinator.autoEnded.first { it.ownerId == owner } }
        coordinator().reconcile()
        assertEquals(AutoEndedShift(owner, id, i(t(15))), withTimeout(5_000) { announced.await() })
        assertNull(database.dao().getOpenShift(owner))
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(i(t(15)).toEpochMilli(), saved.endedAt); assertTrue(saved.autoEnded)
        assertTrue(shows(ShiftNotifications.ID_AUTO_ENDED))
        assertTrue(gone(ShiftNotifications.ID_ONGOING))
        assertEquals(setOf(AlarmKind.START_NUDGE.name), armedKinds())   // nothing re-armed for a shift that should be closed
        assertFalse(pendingExists(AlarmKind.BREAK_DUE, id))
        // Idempotent: the second reconcile finds nothing to end and changes nothing.
        coordinator.reconcile()
        assertEquals(i(t(15)).toEpochMilli(), requireNotNull(database.dao().getSession(owner, id)).endedAt)
        assertEquals(setOf(AlarmKind.START_NUDGE.name), armedKinds())
    }

    @Test
    fun aStaleForecastArmsNoBreakAlarmAndTheTimerSaysSo() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        seedForecast(airC = 33.0, ageMinutes = 7 * 60)          // a 7-hour-old Hot forecast is unknown, not a cadence (spec §5.13)
        val id = shifts.startShift(owner, input(heavy = false))
        clock.advanceMinutes(45)                                  // 7:47 would be due on a current Hot forecast
        val plan = requireNotNull(coordinator.reconcile())
        assertEquals(BreakAlertDecision.Quiet(QuietReason.STALE_FORECAST), plan.breakAlert)
        assertEquals(setOf(AlarmKind.AUTO_END.name, AlarmKind.STILL_ON_SHIFT.name, AlarmKind.REFRESH.name), armedKinds())
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))
        assertEquals(0, events(id).count { it.type == ShiftEventType.ALERT })
        val ongoing = shown(ShiftNotifications.ID_ONGOING) { text(it) == "No current forecast · take your usual breaks" }
        assertEquals("No current forecast · take your usual breaks", text(ongoing))
        // The refreshed forecast lands (ForecastWorker success, or the ViewModel's forecast observer, calls reconcile): the walk
        // resumes and the due break posts: the notification, its ledger row and the repeat alarm. The plan returned is the one
        // applied after the post, planned again on the new ledger row, so its decision is "already alerted", not Post.
        seedForecast(airC = 33.0)
        val fresh = requireNotNull(coordinator.reconcile())
        assertTrue(shows(ShiftNotifications.ID_BREAK_DUE))
        assertEquals(1, events(id).count { it.type == ShiftEventType.ALERT })
        assertEquals(setOf(AlarmKind.AUTO_END.name, AlarmKind.STILL_ON_SHIFT.name, AlarmKind.BREAK_REPEAT.name, AlarmKind.REFRESH.name), armedKinds())
        assertEquals(BreakAlertDecision.Quiet(QuietReason.ALREADY_ALERTED), fresh.breakAlert)
    }

    @Test
    fun aDueBreakPostsOnceRecordsTheLedgerArmsTheRepeatAndSnoozeAsksAgain() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        clock.advanceMinutes(45)                                  // 7:47: due
        coordinator.reconcile()
        assertTrue(shows(ShiftNotifications.ID_BREAK_DUE))
        assertEquals(listOf(AlertReason.BREAK_DUE), events(id).filter { it.type == ShiftEventType.ALERT }.map { it.reason })
        assertEquals(setOf(AlarmKind.AUTO_END.name, AlarmKind.STILL_ON_SHIFT.name, AlarmKind.BREAK_REPEAT.name, AlarmKind.REFRESH.name), armedKinds())
        coordinator.reconcile()
        assertEquals(1, events(id).count { it.type == ShiftEventType.ALERT })          // idempotent
        // Doze held the 7:49 repeat alarm: a reconcile three minutes on finds the repeat owed (unanswered, not repeated, time
        // passed) and posts it itself, re-planning to "already alerted"; the alarms that then arrive, the inexact pair included,
        // find the ledger row and stay quiet. One repeat, then nothing more.
        clock.advanceMinutes(3)
        val held = requireNotNull(coordinator.reconcile())
        assertEquals(listOf(AlertReason.BREAK_DUE, AlertReason.REPEAT), events(id).filter { it.type == ShiftEventType.ALERT }.map { it.reason })
        assertEquals(BreakAlertDecision.Quiet(QuietReason.ALREADY_ALERTED), held.breakAlert)
        coordinator.onAlarm(owner, AlarmKind.BREAK_REPEAT, id, i(t(7, 47)).toEpochMilli())
        coordinator.onAlarm(owner, AlarmKind.BREAK_DUE, id, i(t(7, 47)).toEpochMilli())
        assertEquals(listOf(AlertReason.BREAK_DUE, AlertReason.REPEAT), events(id).filter { it.type == ShiftEventType.ALERT }.map { it.reason })
        assertTrue(armedKinds().none { it == AlarmKind.BREAK_DUE.name || it == AlarmKind.BREAK_REPEAT.name })
        // The hour turns Extreme at 8:00 while the Hot alert is still showing (Global Constraint 3): the break stays due (the walk
        // drained its Hot budget at 7:47, before the hour crossed), the ledger blocks a new post, and the reconcile rewrites the
        // visible alert in place as the stop alert: stop title, Start break and Unwell only, no new row.
        seedForecast(airC = 33.0, extremeFrom = 8)
        clock.advanceMinutes(11)                                  // 8:01
        // On a phone these steps are minutes apart; here they ran within a second. Android sheds a package's notification
        // updates past about five a second (NotificationManagerService's enqueue rate), which would drop the rewrite under
        // test, so the real clock is let past that burst first. The fake clock, which the logic reads, does not move.
        SystemClock.sleep(2_000)
        // The 8:00 REFRESH wake (the next hour) is what reconciles here: offline in a pocket nothing else would until hours later.
        coordinator.onAlarm(owner, AlarmKind.REFRESH, id, null)
        val rewritten = shown(ShiftNotifications.ID_BREAK_DUE) { title(it) == "Stop heavy work outdoors · Extreme 39°" }
        assertEquals(BreakAlertDecision.Quiet(QuietReason.ALREADY_ALERTED), requireNotNull(coordinator.reconcile()).breakAlert)
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(rewritten))
        assertEquals(listOf("Start break", "Unwell"), rewritten.actions.map { it.title.toString() })
        assertEquals(2, events(id).count { it.type == ShiftEventType.ALERT })
        // A queued In 10 min action can still arrive after the stop rewrite. The answer adds no break minutes,
        // clears the alert and the landed stop-aware walk offers no deferred break beyond the stop line.
        coordinator.onAction(owner, AlertAction.SNOOZE, id)
        assertEquals(1, events(id).count { it.type == ShiftEventType.CANT_STOP })
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))
        assertNull(breakDueAt())
        assertEquals(0, requireNotNull(database.dao().getOpenShift(owner)).breakMinutes)
    }

    /** A revised 7 am forecast changes an already due break into a stop; a 9 am stop leaves the 7:47 snooze valid. */
    @Test
    fun aForecastRevisionRewritesAPostedAtAlertWhenTheDueBecomesACurrentStop() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        clock.advanceMinutes(45)
        coordinator.reconcile()
        assertTrue(coordinator.load(owner).evaluation?.due is heatshield.domain.BreakDue.At)
        val original = shown(ShiftNotifications.ID_BREAK_DUE)
        assertEquals(listOf("Start break", "In 10 min", "Unwell"), original.actions.map { it.title.toString() })
        val ledger = events(id).filter { it.type == ShiftEventType.ALERT }
        assertEquals(1, ledger.size)

        seedForecast(airC = 33.0, extremeFrom = 9)
        coordinator.reconcile()
        assertEquals(listOf("Start break", "In 10 min", "Unwell"), shown(ShiftNotifications.ID_BREAK_DUE).actions.map { it.title.toString() })
        assertEquals(ledger, events(id).filter { it.type == ShiftEventType.ALERT })

        seedForecast(airC = 33.0, extremeFrom = 7)
        assertEquals(heatshield.domain.BreakDue.Stop(i(t(7, 2)), heatshield.domain.BreakRule.HEATSHIELD), coordinator.load(owner).evaluation?.due)
        // Let Android's notification update limit recover; only the fake clock determines the shift's time.
        SystemClock.sleep(2_000)
        coordinator.reconcile()
        val rewritten = shown(ShiftNotifications.ID_BREAK_DUE) { title(it) == "Stop heavy work outdoors · Extreme 39°" }
        assertEquals("Stop heavy work outdoors · Extreme 39°", title(rewritten))
        assertEquals(listOf("Start break", "Unwell"), rewritten.actions.map { it.title.toString() })
        assertTrue(rewritten.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(original.`when`, rewritten.`when`)
        coordinator.reconcile()
        assertEquals(ledger, events(id).filter { it.type == ShiftEventType.ALERT })
        assertTrue(armedKinds().none { it == AlarmKind.BREAK_DUE.name || it == AlarmKind.BREAK_REPEAT.name })

        notifications.cancel(ShiftNotifications.ID_BREAK_DUE)
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))
        coordinator.reconcile()
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))
        assertEquals(ledger, events(id).filter { it.type == ShiftEventType.ALERT })
    }

    @Test
    fun aSecondCantStopAnswerKeepsTheDueAlarmButItsFireOnlyUpdatesTheOverdueTimer() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        clock.advanceMinutes(45)                                  // 7:47: first break alert
        coordinator.reconcile()
        assertEquals(1, events(id).count { it.type == ShiftEventType.ALERT })
        coordinator.onAction(owner, AlertAction.SNOOZE, id)
        assertEquals(i(t(7, 57)), breakDueAt())
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))

        clock.advanceMinutes(10)
        coordinator.onAlarm(owner, AlarmKind.BREAK_DUE, id, i(t(7, 57)).toEpochMilli())
        assertTrue(shows(ShiftNotifications.ID_BREAK_DUE))
        assertEquals(listOf(AlertReason.BREAK_DUE, AlertReason.BREAK_DUE), events(id).filter { it.type == ShiftEventType.ALERT }.map { it.reason })
        // The re-ask offers no "In 10 min": a second answer reaches the cap, and its fire would stay silent (spec §8: no promise
        // the app does not keep). Start break and Unwell stay; the sheet the body opens still takes a second answer.
        assertEquals(listOf("Start break", "Unwell"), shown(ShiftNotifications.ID_BREAK_DUE).actions.map { it.title.toString() })
        coordinator.onAction(owner, AlertAction.SNOOZE, id)
        assertEquals(2, events(id).count { it.type == ShiftEventType.CANT_STOP })
        assertEquals(i(t(8, 7)), breakDueAt())
        assertTrue(pendingExists(AlarmKind.BREAK_DUE, id))
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))

        // At the cap this wake is still required: it updates the timer even though the policy owes no more buzzes. (The real
        // clock is let past the burst above first, as in the stop rewrite case: Android sheds updates past about five a second.)
        clock.advanceMinutes(10)
        SystemClock.sleep(2_000)
        coordinator.onAlarm(owner, AlarmKind.BREAK_DUE, id, i(t(8, 7)).toEpochMilli())
        assertEquals(2, events(id).count { it.type == ShiftEventType.ALERT })
        assertTrue(gone(ShiftNotifications.ID_BREAK_DUE))
        assertTrue(armedKinds().none { it == AlarmKind.BREAK_DUE.name || it == AlarmKind.BREAK_REPEAT.name })
        assertTrue(shows(ShiftNotifications.ID_ONGOING))
        val ongoing = shown(ShiftNotifications.ID_ONGOING) { title(it) == "Break due · Hot 33°" }
        assertEquals("Break due · Hot 33°", title(ongoing))
        assertEquals(0, requireNotNull(database.dao().getOpenShift(owner)).breakMinutes)
        assertEquals(BreakAlertDecision.Quiet(QuietReason.CANT_STOP_CAP), requireNotNull(coordinator.reconcile()).breakAlert)
    }

    @Test
    fun notificationActionsWriteRoomThroughTheRepository() = runBlocking {
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.onAction(owner, AlertAction.START_BREAK, id)
        assertNull(events(id).single { it.type == ShiftEventType.BREAK }.endedAt)
        clock.advanceMinutes(12)
        coordinator.onAction(owner, AlertAction.END_BREAK, id)
        assertNotNull(events(id).single { it.type == ShiftEventType.BREAK }.endedAt)
        coordinator.onAction(owner, AlertAction.END_BREAK, id)    // nothing open: swallowed, state unchanged
        assertEquals(1, events(id).size)
        coordinator.onAction(owner, AlertAction.ONGOING_DISMISSED, id)
        assertTrue(coordinator.ongoingDismissed(id))
        coordinator.onAction(owner, AlertAction.END_SHIFT, id)
        assertNull(database.dao().getOpenShift(owner))
        assertEquals(12, requireNotNull(database.dao().getSession(owner, id)).breakMinutes)
        assertFalse(coordinator.ongoingDismissed(id))             // cleared with the shift
        assertEquals(setOf(AlarmKind.START_NUDGE.name), armedKinds())
        // Another owner's action is ignored.
        coordinator.onAction("someone-else", AlertAction.START_BREAK, id)
        assertEquals(1, events(id).size)
    }

    @Test
    fun alertsOffKeepsOnlyTheAutomaticEndAndTheStatusSaysSo() = runBlocking {
        database.dao().savePreferences(requireNotNull(database.dao().getPreferences(owner)).copy(reminders = false))
        shifts.startShift(owner, input(heavy = false))
        val plan = requireNotNull(coordinator.reconcile())
        assertEquals(setOf(AlarmKind.AUTO_END.name), armedKinds())
        assertFalse(plan.ongoing)
        assertTrue(gone(ShiftNotifications.ID_ONGOING))
        val status = coordinator.status(alertsOn = false)
        assertFalse(status.alertsOn); assertEquals(notifications.notificationsAllowed(), status.notificationsAllowed); assertEquals(scheduler.exactAllowed(), status.exactAllowed)
        assertFalse(status.asked); coordinator.markAsked(); assertTrue(coordinator.status(alertsOn = false).asked)
        // The Break-due alerts switch is each owner's, so the question is too: another owner on this phone is asked again.
        coordinator.activateOwner("someone-else")
        assertFalse(coordinator.status(alertsOn = false).asked)
        assertFalse(coordinator.asked("someone-else")); assertTrue(coordinator.asked(owner))
        coordinator.activateOwner(owner)
        assertTrue(coordinator.status(alertsOn = false).asked)
    }

    /**
     * "Still working" on the "Still on shift?" check (spec §4): the confirmed time becomes the shift's planned finish, so the
     * next check comes 30 minutes after it and the automatic end two hours after it, and a shift then forgotten ends at the
     * last confirmed time, keeping the overtime just confirmed. The check itself posts only once the planned finish passed.
     */
    @Test
    fun stillWorkingMovesTheNextCheckAndTheAutomaticEnd() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        fun armedAt(kind: AlarmKind) = scheduler.armed(owner).firstOrNull { it.kind == kind.name }?.atMillis?.let(Instant::ofEpochMilli)
        val id = shifts.startShift(owner, input(heavy = false))   // 7:02, planned finish 15:00
        coordinator.reconcile()
        assertEquals(i(t(15, 30)), armedAt(AlarmKind.STILL_ON_SHIFT)); assertEquals(i(t(17)), armedAt(AlarmKind.AUTO_END))
        clock.advanceMinutes(7 * 60 + 48)                         // 14:50: a check that arrives before the finish posts nothing
        coordinator.onAlarm(owner, AlarmKind.STILL_ON_SHIFT, id, null)
        SystemClock.sleep(500)
        assertNull(active(ShiftNotifications.ID_OVERRUN))
        clock.advanceMinutes(40)                                  // 15:30
        coordinator.onAlarm(owner, AlarmKind.STILL_ON_SHIFT, id, null)
        val finish = Formats.time(i(t(15)), DateFormat.is24HourFormat(context))
        assertEquals("Your shift was set to finish at $finish. End your shift, or keep going.", text(shown(ShiftNotifications.ID_OVERRUN)))
        coordinator.onAction(owner, AlertAction.STILL_WORKING, id)
        assertTrue(gone(ShiftNotifications.ID_OVERRUN))
        assertEquals(i(t(15, 30)).toEpochMilli(), requireNotNull(database.dao().getOpenShift(owner)).plannedEndAt)
        assertEquals(i(t(16)), armedAt(AlarmKind.STILL_ON_SHIFT)); assertEquals(i(t(17, 30)), armedAt(AlarmKind.AUTO_END))
        // A second "Still working" at the same minute moves nothing; an action for another shift writes nothing.
        coordinator.onAction(owner, AlertAction.STILL_WORKING, id)
        coordinator.onAction(owner, AlertAction.STILL_WORKING, id + 1000)
        assertEquals(i(t(15, 30)).toEpochMilli(), requireNotNull(database.dao().getOpenShift(owner)).plannedEndAt)
        clock.advanceMinutes(95)                                  // 17:05: past the old automatic end, before the new one
        coordinator.reconcile()
        assertNotNull(database.dao().getOpenShift(owner))
        clock.advanceMinutes(26)                                  // 17:31: two hours after the confirmed 15:30
        coordinator.reconcile()
        assertNull(database.dao().getOpenShift(owner))
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(i(t(15, 30)).toEpochMilli(), saved.endedAt); assertTrue(saved.autoEnded)
        assertEquals(setOf(AlarmKind.START_NUDGE.name), armedKinds())
    }

    /**
     * The hot-day start nudge's fire (spec §10 M3), decided again from Room: it posts only on a Hot day or hotter, on a current
     * forecast, with no shift open and none tracked that day; the site and the peak are in it.
     */
    @Test
    fun aNudgeFirePostsOnlyOnAHotDayWithNoShiftTrackedThatDay() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        suspend fun fire() { notifications.cancel(ShiftNotifications.ID_NUDGE); SystemClock.sleep(300); coordinator.onAlarm(owner, AlarmKind.START_NUDGE, 0, null) }
        fun nothingPosted(): Boolean { SystemClock.sleep(1_000); return active(ShiftNotifications.ID_NUDGE) == null }
        clock.wall = i(t(6, 40)).toEpochMilli()                    // the usual 7:00 start less 20 minutes
        // Check the gates before any successful delivery, so a delivery marker cannot mask a broken gate.
        seedForecast(airC = 20.0)
        fire()
        assertTrue(nothingPosted())
        // A shift open: nothing (the reconcile after a fire also clears a nudge while a shift runs).
        seedForecast(airC = 33.0)
        shifts.startShift(owner, StartShiftInput("Royal Park", "Push mowing", heavyWork = false, heavyClothing = false, startedAt = t(6, 30), plannedEnd = t(15)))
        fire()
        assertTrue(nothingPosted())
        // A shift already tracked that day, ended: nothing either.
        clock.advanceMinutes(5)
        shifts.endShift(owner, emptyList())
        fire()
        assertTrue(nothingPosted())
        // Tomorrow has no tracked shift; its hot forecast must still produce the reminder.
        clock.wall = i(today.plusDays(1).atTime(6, 40)).toEpochMilli()
        seedForecast(airC = 33.0, day = today.plusDays(1))
        fire()
        val nudge = shown(ShiftNotifications.ID_NUDGE)
        assertEquals("Hot day at Royal Park", title(nudge))
        assertTrue(text(nudge), text(nudge)!!.startsWith("33° by "))
        assertEquals(ShiftNotifications.CHANNEL_OUTLOOK, nudge.channelId)
    }

    @Test
    fun reconcileDeliversAPendingLateNudgeOnceEvenAfterTheCoordinatorIsRecreated() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        clock.wall = i(t(6, 39)).toEpochMilli()
        seedForecast(33.0)
        coordinator.reconcile()
        assertTrue(pendingExists(AlarmKind.START_NUDGE, 0))
        assertFalse(scheduler.armed(owner).single().exact)
        clock.advanceMinutes(3) // 06:42: Android has not delivered the inexact 06:40 alarm.
        coordinator.reconcile()
        assertTrue(shows(ShiftNotifications.ID_NUDGE))
        notifications.cancel(ShiftNotifications.ID_NUDGE)
        assertTrue(gone(ShiftNotifications.ID_NUDGE))
        coordinator = coordinator()
        coordinator.reconcile()
        coordinator.onAlarm(owner, AlarmKind.START_NUDGE, 0, null)
        SystemClock.sleep(500) // Give Android time to expose an unwanted asynchronous re-post.
        assertTrue(gone(ShiftNotifications.ID_NUDGE))
    }

    @Test
    fun reconcileDropsAPendingNudgeAfterItsLateWindowExpires() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        clock.wall = i(t(6, 39)).toEpochMilli()
        seedForecast(33.0)
        coordinator.reconcile()
        assertTrue(pendingExists(AlarmKind.START_NUDGE, 0))
        clock.advanceMinutes(81) // 08:00 is excluded for the usual 07:00 start.
        coordinator.reconcile()
        coordinator.onAlarm(owner, AlarmKind.START_NUDGE, 0, null)
        SystemClock.sleep(500)
        assertTrue(gone(ShiftNotifications.ID_NUDGE))
    }

    @Test
    fun reconcileDeliversAPendingLateOverrunOnceAndKeepsANewFinishEligible() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        clock.advanceMinutes(8 * 60 + 27) // 15:29, one minute before the check.
        coordinator.reconcile()
        assertTrue(pendingExists(AlarmKind.STILL_ON_SHIFT, id))
        assertFalse(scheduler.armed(owner).single { it.kind == AlarmKind.STILL_ON_SHIFT.name }.exact)
        clock.advanceMinutes(2)
        coordinator.reconcile()
        assertTrue(shows(ShiftNotifications.ID_OVERRUN))
        notifications.cancel(ShiftNotifications.ID_OVERRUN)
        assertTrue(gone(ShiftNotifications.ID_OVERRUN))
        coordinator = coordinator()
        coordinator.reconcile()
        coordinator.onAlarm(owner, AlarmKind.STILL_ON_SHIFT, id, null)
        SystemClock.sleep(500)
        assertTrue(gone(ShiftNotifications.ID_OVERRUN))
        coordinator.onAction(owner, AlertAction.STILL_WORKING, id) // New finish 15:31, new check 16:01.
        coordinator.onAlarm(owner, AlarmKind.STILL_ON_SHIFT, id, null)
        SystemClock.sleep(500)
        assertTrue(gone(ShiftNotifications.ID_OVERRUN))
        clock.advanceMinutes(30)
        coordinator.reconcile()
        assertTrue(shows(ShiftNotifications.ID_OVERRUN))
    }

    @Test
    fun aLateOverrunFireAfterAutomaticEndDoesNotReviveTheNotice() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        coordinator.reconcile()
        clock.advanceMinutes(10 * 60) // 17:02: the two-hour grace after 15:00 has expired.
        coordinator.onAlarm(owner, AlarmKind.STILL_ON_SHIFT, id, null)
        assertNull(database.dao().getOpenShift(owner))
        assertTrue(gone(ShiftNotifications.ID_OVERRUN))
        assertTrue(shows(ShiftNotifications.ID_AUTO_ENDED))
    }

    @Test
    fun aNudgeBeforeMidnightUsesTomorrowsHeatAtTheActualCacheAge() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        val date = LocalDate.of(2026, 10, 8)
        clock.wall = i(date.atTime(23, 50)).toEpochMilli()
        val preferences = requireNotNull(database.dao().getPreferences(owner))
        database.dao().savePreferences(preferences.copy(shiftStartMinute = 10, shiftEndMinute = 8 * 60 + 10))
        seedForecast(33.0, ageMinutes = 5 * 60 + 50, day = date.plusDays(1))
        coordinator.onAlarm(owner, AlarmKind.START_NUDGE, 0, null)
        assertEquals("Hot day at Royal Park", title(shown(ShiftNotifications.ID_NUDGE)))
    }

    @Test
    fun aNudgeBeforeMidnightRejectsACacheActuallyOlderThanSixHours() = runBlocking {
        assumeTrue(notifications.notificationsAllowed())
        val date = LocalDate.of(2026, 10, 8)
        clock.wall = i(date.atTime(23, 50)).toEpochMilli()
        val preferences = requireNotNull(database.dao().getPreferences(owner))
        database.dao().savePreferences(preferences.copy(shiftStartMinute = 10, shiftEndMinute = 8 * 60 + 10))
        seedForecast(33.0, ageMinutes = 6 * 60 + 1, day = date.plusDays(1))
        coordinator.onAlarm(owner, AlarmKind.START_NUDGE, 0, null)
        SystemClock.sleep(500)
        assertTrue(gone(ShiftNotifications.ID_NUDGE))
    }

    /**
     * UIC-02: background refresh follows the work park across midnight with the app closed. The usual park is Birrarung
     * Marr and a plan for tomorrow names Royal Park. A reconcile at 23:59 points refresh at Birrarung Marr; the first one
     * after midnight (the next ForecastWorker run or alarm) points it at Royal Park, with no row written in between, so the
     * runs after it keep Royal Park's cache current for the morning's start nudge. Signing out then stops it.
     */
    @Test
    fun aReconcileAfterMidnightPointsBackgroundRefreshAtTheNewDaysPlannedPark() = runBlocking {
        val dao = database.dao()
        dao.savePreferences(requireNotNull(dao.getPreferences(owner)).copy(site = "Birrarung Marr"))
        val plan = heatshield.data.ShiftPlan(ownerId = owner, site = "Royal Park",
            dayMillis = today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        dao.savePlan(plan)
        clock.wall = i(t(23, 59)).toEpochMilli()
        assertNotNull(coordinator.reconcile())
        assertEquals(listOf("Birrarung Marr"), refreshed.map { it?.name })
        clock.advanceMinutes(2)                                   // 00:01 tomorrow; nothing in Room changes
        assertNotNull(coordinator.reconcile())
        assertEquals(listOf("Birrarung Marr", "Royal Park"), refreshed.map { it?.name })
        assertEquals(plan, dao.getPlan(owner))
        coordinator.activateOwner(null)
        assertEquals(listOf("Birrarung Marr", "Royal Park", null), refreshed.map { it?.name })
    }

    /**
     * The owner boundary (Global Constraint 2). An owner switch asked for while a reconcile is inside its Room reads waits
     * behind ownerLock and then takes everything that reconcile armed or posted; a Firebase sign-out waits for nothing and is
     * caught by the re-check after the read. Either way the old owner is left nothing: no alarm, no timer, no break alert,
     * no automatic-end notice. The shift itself stays in Room for its owner.
     */
    @Test
    fun anOwnerSwitchDuringASuspendedReconcilePostsNothingForTheOldOwnerAndLeavesNoOldAlarm() = runBlocking {
        assumeTrue("The test AVD allows HeatShield notifications (README preconditions).", notifications.notificationsAllowed())
        val id = shifts.startShift(owner, input(heavy = false))
        val ownerIds = listOf(ShiftNotifications.ID_ONGOING, ShiftNotifications.ID_BREAK_DUE, ShiftNotifications.ID_AUTO_ENDED)
        // 1. The switch arrives mid-read. The sign-in check is the first thing a reconcile asks inside the boundary, so a fake
        //    that asks for the switch right there has it pending for the whole Room read, as the ViewModel's would be.
        var switch: Deferred<Unit>? = null
        val racing = coordinator { candidate ->
            if (switch == null) switch = async(Dispatchers.IO) { coordinator.activateOwner("someone-else") }
            candidate == owner
        }
        racing.reconcile()
        requireNotNull(switch).await()
        assertEquals("someone-else", coordinator.activeOwner())
        assertTrue(scheduler.armed(owner).isEmpty())
        // The racing reconcile pointed background refresh at the shift's park; the switch then stopped it with the alarms.
        assertEquals(listOf("Royal Park", null), refreshed.map { it?.name })
        assertFalse(pendingExists(AlarmKind.BREAK_DUE, id)); assertFalse(pendingExists(AlarmKind.AUTO_END, id))
        ownerIds.forEach { assertTrue("notification $it", gone(it)) }
        assertNotNull(database.dao().getOpenShift(owner))        // untouched: the shift waits for its owner
        // 2. A sign-out lands during the read instead: the gate passed, the re-check after the read does not. Nothing is armed
        //    or shown, and the owner is released before the ViewModel's activateOwner(null) even arrives.
        coordinator.activateOwner(owner)
        var checks = 0
        val signingOut = coordinator { candidate -> candidate == owner && checks++ == 0 }
        val before = refreshed.size
        assertNull(signingOut.reconcile())
        assertTrue(scheduler.armed(owner).isEmpty())
        assertEquals("a signed-out owner's park is never pointed at, only stopped", listOf(null), refreshed.drop(before))
        ownerIds.forEach { assertTrue("notification $it", gone(it)) }
        coordinator.activateOwner(null)                          // the follow-up finds nothing left to release
        assertNull(coordinator.activeOwner())
        assertNotNull(database.dao().getOpenShift(owner))
    }
}
