package heatshield

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.background.AlertScheduler
import heatshield.background.AlarmSink
import heatshield.background.AndroidAlarmSink
import heatshield.domain.AlarmKind
import heatshield.domain.AlertPlan
import heatshield.domain.BreakAlertDecision
import heatshield.domain.PlannedAlarm
import heatshield.domain.QuietReason
import heatshield.domain.ShiftRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Checks the phone's alarm requests: only break alarms use precise timing when allowed; other shift reminders stay inexact.
 * Without permission, each break gets a window and an idle-capable fallback. For example, the 07:45 break has two
 * operations sharing its due time, while the 15:30 Still on shift reminder has one inexact operation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AlertSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val shadow = shadowOf(alarmManager)
    private val scheduler = AlertScheduler(context)
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    private fun i(at: LocalDateTime): Instant = ShiftRules.instant(at)
    private val now = i(t(7, 10))
    private val elapsedNow = 3_600_000L
    private val quiet = BreakAlertDecision.Quiet(QuietReason.NOT_DUE)
    private val onShift = AlertPlan(listOf(
        PlannedAlarm(AlarmKind.AUTO_END, i(t(17)), 7), PlannedAlarm(AlarmKind.STILL_ON_SHIFT, i(t(15, 30)), 7), PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7, 45)), 7, i(t(7, 45)))
    ), ongoing = true, breakAlert = quiet)
    private fun minutes(n: Long) = n * 60_000L

    @Before
    fun exactAllowedByDefault() {
        // ShadowAlarmManager.setCanScheduleExactAlarms is a static setter (Java static: called on the class, never on `shadow`),
        // so its value outlives a test; every case starts from "allowed" and sets "denied" itself where it needs it.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun exactAllowedMakesOnlyBreakAlarmsPreciseOnTheElapsedClock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.exactAllowed())
        val armed = scheduler.apply("local", onShift, now, elapsedNow)
        assertEquals(3, shadow.scheduledAlarms.size)
        assertEquals(armed, scheduler.armed("local"))
        assertTrue(shadow.scheduledAlarms.all { it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP })
        assertEquals(setOf(elapsedNow + minutes(35), elapsedNow + minutes(8 * 60 + 20), elapsedNow + minutes(9 * 60 + 50)),
            shadow.scheduledAlarms.map { it.triggerAtTime }.toSet())
        val breakDue = armed.single { it.kind == AlarmKind.BREAK_DUE.name }
        assertTrue(breakDue.exact); assertEquals(i(t(7, 45)).toEpochMilli(), breakDue.dueAtMillis)
        assertEquals(AlertScheduler.requestCode(AlarmKind.BREAK_DUE, 7, 0), breakDue.code)
        assertFalse(armed.single { it.kind == AlarmKind.AUTO_END.name }.exact)   // only break alarms want the minute
        val scheduledBreak = shadow.scheduledAlarms.single { it.triggerAtTime == elapsedNow + minutes(35) }
        assertEquals(0L, scheduledBreak.windowLengthMs)
        assertTrue(shadow.scheduledAlarms.all { it.allowWhileIdle })
        assertTrue(shadow.scheduledAlarms.filter { it != scheduledBreak }.all { it.windowLengthMs == -1L })
    }

    @Test
    fun exactDeniedArmsAWindowAndAnIdlePairThatShareTheTrigger() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.exactAllowed())
        val armed = scheduler.apply("local", onShift, now, elapsedNow)
        assertEquals(4, shadow.scheduledAlarms.size)
        val pair = shadow.scheduledAlarms.filter { it.triggerAtTime == elapsedNow + minutes(35) }
        assertEquals(2, pair.size)
        val codes = armed.filter { it.kind == AlarmKind.BREAK_DUE.name }.map { it.code }
        assertEquals(listOf(AlertScheduler.requestCode(AlarmKind.BREAK_DUE, 7, 0), AlertScheduler.requestCode(AlarmKind.BREAK_DUE, 7, 1)), codes)
        assertTrue(armed.none { it.exact })
        // The two operations carry the same due time and differ only by the pair flag; the receiver de-duplicates through Room.
        val dues = pair.map { shadowOf(it.operation).savedIntent.getLongExtra(AlertScheduler.EXTRA_DUE, -1L) }.toSet()
        assertEquals(setOf(i(t(7, 45)).toEpochMilli()), dues)
        assertEquals(setOf(0, 1), pair.map { shadowOf(it.operation).savedIntent.getIntExtra(AlertScheduler.EXTRA_PAIR, -1) }.toSet())
        assertEquals(600_000L, pair.single { !it.allowWhileIdle }.windowLengthMs)
        assertEquals(-1L, pair.single { it.allowWhileIdle }.windowLengthMs)

        // Permission can also be revoked after exactAllowed(): the same apply must fall back to the same pair.
        scheduler.cancelAll("local")
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        var exactCalls = 0
        val revoked = AlertScheduler(context, object : AlarmSink by AndroidAlarmSink(context) {
            override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
                exactCalls++
                throw SecurityException("Precise timing revoked")
            }
        })
        val fallback = revoked.apply("local", onShift, now, elapsedNow)
        assertEquals(1, exactCalls)
        assertEquals(armed, fallback)
        assertEquals(fallback, revoked.armed("local"))
        assertEquals(4, shadow.scheduledAlarms.size)
        val fallbackPair = shadow.scheduledAlarms.filter { it.triggerAtTime == elapsedNow + minutes(35) }
        assertEquals(2, fallbackPair.size)
        assertEquals(600_000L, fallbackPair.single { !it.allowWhileIdle }.windowLengthMs)
        assertEquals(-1L, fallbackPair.single { it.allowWhileIdle }.windowLengthMs)
        revoked.cancelAll("local")
        assertTrue(shadow.scheduledAlarms.isEmpty())
        assertTrue(revoked.armed("local").isEmpty())
    }

    @Test
    fun applyingANewPlanCancelsTheOldCodesAndTheSamePlanTwiceIsIdempotent() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.apply("local", onShift, now, elapsedNow)
        scheduler.apply("local", onShift, now, elapsedNow)
        assertEquals(3, shadow.scheduledAlarms.size)
        // Heavy work moved the break: the plan says 7:30 and the old 7:45 alarm must go.
        val moved = onShift.copy(alarms = onShift.alarms.map { if (it.kind == AlarmKind.BREAK_DUE) it.copy(at = i(t(7, 30)), dueAt = i(t(7, 30))) else it })
        scheduler.apply("local", moved, now, elapsedNow)
        assertEquals(3, shadow.scheduledAlarms.size)
        assertEquals(1, shadow.scheduledAlarms.count { it.triggerAtTime == elapsedNow + minutes(20) })
        assertEquals(0, shadow.scheduledAlarms.count { it.triggerAtTime == elapsedNow + minutes(35) })
        // Permission revoked after arming exact: the next apply arms the pair instead, still three planned alarms, four operations.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.apply("local", moved, now, elapsedNow)
        assertEquals(4, shadow.scheduledAlarms.size)
        scheduler.cancelAll("local")
        assertEquals(0, shadow.scheduledAlarms.size)
        assertTrue(scheduler.armed("local").isEmpty())
        // Another owner's record is untouched by this owner's cancel.
        scheduler.apply("uid-x", onShift, now, elapsedNow)
        scheduler.cancelAll("local")
        assertEquals(4, shadow.scheduledAlarms.size)
        val otherOwner = scheduler.armed("uid-x")
        scheduler.apply("local", moved, now, elapsedNow)
        assertEquals(8, shadow.scheduledAlarms.size)
        // Cancellation still finds the persisted operations after the scheduler instance is recreated.
        val recreated = AlertScheduler(context)
        recreated.cancelAll("local")
        assertEquals(4, shadow.scheduledAlarms.size)
        assertEquals(otherOwner, recreated.armed("uid-x"))
        assertTrue(shadow.scheduledAlarms.all {
            shadowOf(it.operation).savedIntent.getStringExtra(AlertScheduler.EXTRA_OWNER) == "uid-x"
        })
    }

    @Test
    fun theNudgeIsAWallClockAlarmAndOffShiftPlansHaveNothingElse() {
        val offShift = AlertPlan(listOf(PlannedAlarm(AlarmKind.START_NUDGE, i(t(6, 40)))), ongoing = false, breakAlert = BreakAlertDecision.Quiet(QuietReason.NO_SHIFT))
        val armed = scheduler.apply("local", offShift, now, elapsedNow)
        val nudge = shadow.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, nudge.type)
        assertEquals(i(t(6, 40)).toEpochMilli(), nudge.triggerAtTime)
        assertEquals(-1L, nudge.windowLengthMs)
        assertTrue(nudge.allowWhileIdle)
        assertEquals(AlertScheduler.requestCode(AlarmKind.START_NUDGE, 0, 0), armed.single().code)
        val saved = shadowOf(nudge.operation).savedIntent
        assertEquals(AlertScheduler.ACTION_FIRE, saved.action)
        assertEquals("local", saved.getStringExtra(AlertScheduler.EXTRA_OWNER))
        assertEquals(AlarmKind.START_NUDGE.name, saved.getStringExtra(AlertScheduler.EXTRA_KIND))
        assertEquals("heatshield://alert/local/START_NUDGE/0", saved.dataString)
    }

    @Test
    fun aRefreshIsAPlainWindowThatNeverSpendsTheIdleRation() {
        // REFRESH only rebuilds the lock-screen words (the next hour, a stop, a smoko's end). It is appended to AlarmKind, so every
        // existing request code stays as it was, and it is armed without allow-while-idle, so Doze's ration of allow-while-idle
        // wakes is left for the break alarms; exact or not, it never asks for the minute.
        assertEquals(listOf(0, 2, 4, 6, 8, 10), AlarmKind.entries.map { AlertScheduler.requestCode(it, 0, 0) })
        assertEquals(710, AlertScheduler.requestCode(AlarmKind.REFRESH, 7, 0))
        listOf(true, false).forEach { exact ->
            ShadowAlarmManager.setCanScheduleExactAlarms(exact)
            val plan = onShift.copy(alarms = onShift.alarms + PlannedAlarm(AlarmKind.REFRESH, i(t(8)), 7))
            val armed = scheduler.apply("local", plan, now, elapsedNow)
            val refresh = armed.single { it.kind == AlarmKind.REFRESH.name }
            assertEquals(710, refresh.code); assertFalse(refresh.exact)
            val alarm = shadow.scheduledAlarms.single { it.triggerAtTime == elapsedNow + minutes(50) }
            assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
            assertFalse(alarm.allowWhileIdle)
            assertEquals(600_000L, alarm.windowLengthMs)
            assertEquals(AlarmKind.REFRESH.name, shadowOf(alarm.operation).savedIntent.getStringExtra(AlertScheduler.EXTRA_KIND))
            scheduler.cancelAll("local")
            assertTrue(shadow.scheduledAlarms.isEmpty())
        }
    }

    @Test
    fun aPlannedTimeAlreadyPassedOnTheTimelineFiresAtOnce() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val late = AlertPlan(listOf(PlannedAlarm(AlarmKind.BREAK_REPEAT, i(t(7, 5)), 7, i(t(7, 3)))), ongoing = true, breakAlert = quiet)
        scheduler.apply("local", late, now, elapsedNow)
        assertEquals(elapsedNow, shadow.scheduledAlarms.single().triggerAtTime)   // never in the past: coerced to now
    }
}
