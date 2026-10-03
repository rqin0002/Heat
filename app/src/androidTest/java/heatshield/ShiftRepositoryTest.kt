package heatshield

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.AlertReason
import heatshield.data.BreakEdit
import heatshield.data.BreakReason
import heatshield.data.EventContext
import heatshield.data.HeatShieldDatabase
import heatshield.data.PastShiftDraft
import heatshield.data.PreviewShiftResult
import heatshield.data.ShiftClock
import heatshield.data.ShiftError
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftFailure
import heatshield.data.ShiftRepository
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.ShiftTimesEdit
import heatshield.data.StartShiftInput
import heatshield.data.UserPreferences
import heatshield.data.WorkSession
import heatshield.domain.BreakRule
import heatshield.domain.BreakTimes
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.OpenAnswerKind
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.ui.fixTimesEdit
import heatshield.ui.minuteOf
import heatshield.ui.toBreakRows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The one writer, on a disposable database with a fake clock: one open shift, measured breaks, recovery, derived totals. */
@RunWith(AndroidJUnit4::class)
class ShiftRepositoryTest {
    private class FakeShiftClock(var wall: Long, var elapsed: Long, var boot: Int) : ShiftClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        override fun bootCount() = boot
        fun advanceMinutes(minutes: Long) { wall += minutes * 60_000L; elapsed += minutes * 60_000L }
        /** A reboot: wall time moves on, monotonic time restarts, the boot count ticks. */
        fun rebootAfterMinutes(minutes: Long) { wall += minutes * 60_000L; elapsed = 5_000L; boot += 1 }
    }

    private lateinit var context: Context
    private lateinit var database: HeatShieldDatabase
    private lateinit var clock: FakeShiftClock
    private lateinit var shifts: ShiftRepository
    private val databaseName = "shift-repository-test-${UUID.randomUUID()}.db"
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    /** The shift's timeline is real instants; this day has no daylight-saving change, so [t] converts one to one. */
    private fun i(time: LocalDateTime): Instant = ShiftRules.instant(time)
    private val owner = "worker-a"
    private val hotDay: List<HourHeat> = (6..17).map { h ->
        val air = if (h in 11..13) 36.0 else 33.0
        val base = HeatRules.baseLevel(air)
        HourHeat(t(h), air, 10.0, 8.0, air - 3.0, air, base, base, HeatRules.wholeDegrees(air) >= 35, 5.0, 5.0)
    }

    @Before
    fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = HeatShieldDatabase.builder(context, databaseName).build()
        clock = FakeShiftClock(wall = ShiftRules.epochMillis(t(7, 2)), elapsed = 3_600_000L, boot = 12)
        shifts = ShiftRepository(database, clock)
        runBlocking { database.dao().savePreferences(UserPreferences(ownerId = owner, setupDone = true)) }
    }

    @After
    fun close() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.deleteDatabase(databaseName)
    }

    private fun input(start: LocalDateTime = t(7, 2), end: LocalDateTime = t(15), heavy: Boolean = true) =
        StartShiftInput("Birrarung Marr", "Push mowing", heavyWork = heavy, heavyClothing = false, startedAt = start, plannedEnd = end)
    private val noContext = EventContext(level = HeatLevel.HOT, heatC = 33.0, dueAt = null)
    private suspend fun openShift() = requireNotNull(shifts.observeOpenShift(owner).first())
    private suspend fun expectFailure(expected: ShiftError, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $expected, got $error", error is ShiftFailure && error.error == expected)
    }

    @Test
    fun oneOpenShiftPerOwnerAndItIsRecoveredByANewRepositoryOnTheSameBoot() = runBlocking {
        val id = shifts.startShift(owner, input())
        expectFailure(ShiftError.SHIFT_OPEN) { shifts.startShift(owner, input()) }
        expectFailure(ShiftError.IN_FUTURE) { shifts.startShift("worker-b", input(start = t(7, 3))) }
        expectFailure(ShiftError.TOO_LONG) { shifts.startShift("worker-b", input(end = t(7, 2).plusHours(17))) }
        val saved = openShift().shift
        assertEquals(id, saved.id); assertEquals(ShiftSource.TRACKED, saved.source); assertTrue(saved.heavyWork); assertEquals("15 Jan 2026", saved.date)
        assertEquals(12, saved.bootCount)
        // Process death: a fresh repository over the same database and the same boot keeps counting monotonically,
        // even when the wall clock is edited.
        clock.advanceMinutes(30)
        clock.wall -= 20 * 60_000L   // the gardener's phone clock set back 20 minutes
        val relaunched = ShiftRepository(database, clock)
        val recovered = requireNotNull(relaunched.observeOpenShift(owner).first())
        assertEquals(id, recovered.shift.id)
        assertEquals(i(t(7, 32)), relaunched.now(recovered.shift))
    }

    @Test
    fun aFinishedShiftKeepsItsTimesWhenOnlyItsNoteChangesAfterABackwardClockChange() = runBlocking {
        clock.wall = ShiftRules.epochMillis(t(7))
        val id = shifts.startShift(owner, input(start = t(7)))
        clock.advanceMinutes(105)
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(10)
        shifts.endBreak(owner)
        clock.advanceMinutes(5)
        clock.wall -= 20 * 60_000L
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        val events = database.dao().getEvents(id)
        assertEquals(ShiftRules.epochMillis(t(9)), saved.endedAt)
        assertEquals(ShiftRules.epochMillis(t(8, 40)), clock.wall)
        val edit = fixTimesEdit(saved, events, date, minuteOf(requireNotNull(saved.startedAt)), minuteOf(requireNotNull(saved.endedAt)),
            events.toBreakRows(saved, 570, 720), "Clock corrected")
        assertTrue(requireNotNull(edit.validation(saved, events, Instant.ofEpochMilli(clock.wall))).ok)
        shifts.editTimes(owner, id, edit, hotDay)
        assertEquals(saved.copy(note = "Clock corrected"), database.dao().getSession(owner, id))
        assertEquals(events, database.dao().getEvents(id))
        expectFailure(ShiftError.INVALID_TIMES) { shifts.editTimes(owner, id, edit.copy(end = t(8, 50)), hotDay) }
        expectFailure(ShiftError.INVALID_TIMES) { shifts.editTimes(owner, id, edit.copy(end = t(9, 1)), hotDay) }
    }

    @Test
    fun rebootRecoversTheShiftAndItsOpenBreakFromWallTimeInsteadOfClearing() = runBlocking {
        shifts.startShift(owner, input())
        clock.advanceMinutes(58)
        val breakId = shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.rebootAfterMinutes(7)
        val afterReboot = ShiftRepository(database, clock)
        val recovered = requireNotNull(afterReboot.observeOpenShift(owner).first())
        assertEquals(i(t(8, 7)), afterReboot.now(recovered.shift))
        val openBreak = recovered.events.single { it.id == breakId }
        assertNull(openBreak.endedAt)
        val evaluation = ShiftRules.evaluate(recovered, hotDay, BreakRule.HEATSHIELD, 570, 720, afterReboot.now(recovered.shift))
        assertEquals(t(8), evaluation.openBreak?.let { ShiftRules.localTime(it.startedAt) })
        afterReboot.endBreak(owner)
        val closed = openShift().events.single()
        assertEquals(t(8, 7), ShiftRules.localTime(requireNotNull(closed.endedAt)))
    }

    @Test
    fun breaksResetOrPauseTheClockAndCantStopAddsNothing() = runBlocking {
        shifts.startShift(owner, input())
        clock.advanceMinutes(58)                      // 8:00
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        expectFailure(ShiftError.BREAK_OPEN) { shifts.startBreak(owner, BreakReason.SHADE, noContext) }
        clock.advanceMinutes(12); shifts.endBreak(owner)   // 8:12, a 12-minute break resets
        expectFailure(ShiftError.NO_BREAK) { shifts.endBreak(owner) }
        clock.advanceMinutes(20)                      // 8:32
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(5); shifts.endBreak(owner)    // 8:37, a 5-minute pause
        clock.advanceMinutes(3)                       // 8:40
        shifts.cantStop(owner, noContext.copy(dueAt = i(t(8, 57))))
        val evaluation = ShiftRules.evaluate(openShift(), hotDay, BreakRule.HEATSHIELD, 570, 720, shifts.now(openShift().shift))
        assertEquals(i(t(8, 12)), evaluation.clock.since)
        assertEquals(23, evaluation.clock.workedMinutes)   // 28 minutes since 8:12 less the 5-minute pause
        assertEquals(OpenAnswerKind.CANT_STOP, evaluation.openAnswer)
        assertEquals(i(t(8, 50)), evaluation.snoozedUntil)
        val cantStop = openShift().events.single { it.type == ShiftEventType.CANT_STOP }
        assertEquals(cantStop.startedAt, cantStop.endedAt)
        assertEquals(ShiftRules.epochMillis(t(8, 57)), cantStop.dueAt)
        clock.advanceMinutes(20)                      // 9:00
        val id = shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(17, saved.breakMinutes)          // 12 + 5; the answer adds nothing
        assertEquals(118 - 17, saved.outdoorMinutes)  // 7:02–9:00
        assertEquals(ShiftStatus.NONE, saved.status)
        assertNull(shifts.observeOpenShift(owner).first())
    }

    @Test
    fun recordAlertWritesAZeroLengthRowThatFixTimesKeeps() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(45)
        shifts.recordAlert(owner, id, AlertReason.BREAK_DUE, EventContext(HeatLevel.HOT, 33.0, i(t(7, 45))), shifts.now(openShift().shift))
        val alert = openShift().events.single()
        assertEquals(ShiftEventType.ALERT, alert.type); assertEquals(alert.startedAt, alert.endedAt)
        assertEquals(AlertReason.BREAK_DUE, alert.reason); assertEquals(ShiftRules.epochMillis(t(7, 45)), alert.dueAt); assertEquals("HOT", alert.level)
        // Still no open break, so a break can start; the alert is not an answer either.
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(12); shifts.endBreak(owner)
        clock.advanceMinutes(61); shifts.endShift(owner, hotDay)   // 9:00 on the fake clock, so the 9:00 finish below is not "in the future" (validateTimes reads wall now)
        assertEquals(12, requireNotNull(database.dao().getSession(owner, id)).breakMinutes)
        shifts.editTimes(owner, id, ShiftTimesEdit(t(7), t(9), listOf(BreakEdit(1, null, null, t(8), t(8, 10), taken = true)), note = null), hotDay)
        val types = database.dao().getEvents(id).map { it.type }
        assertEquals(listOf(ShiftEventType.ALERT, ShiftEventType.BREAK), types)   // history kept, break rewritten
        expectFailure(ShiftError.NO_SHIFT) { shifts.recordAlert("worker-b", id, AlertReason.REPEAT, noContext, i(t(10))) }
    }

    @Test
    fun noShadeFlagsTheShiftAndEndShiftWritesDerivedTotalsFromTheHeat() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(178)                     // 10:00
        shifts.startBreak(owner, BreakReason.NO_SHADE, noContext)
        assertEquals(ShiftStatus.TO_RAISE, openShift().shift.status)
        clock.advanceMinutes(15); shifts.endBreak(owner)   // 10:15
        clock.advanceMinutes(285)                     // 15:00
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        // Smoko 9:30 and lunch 12:00 passed unanswered. They are proposals, not breaks: nothing is added, and the
        // summary lists them unticked for Fix times.
        assertEquals(15, saved.breakMinutes)
        assertEquals(480 - 15, saved.outdoorMinutes)
        assertEquals(HeatLevel.VERY_HOT.name, saved.peakLevel)
        assertEquals(36.0, requireNotNull(saved.peakHeat), 0.0)
        assertEquals(0, saved.normalMinutes); assertEquals(saved.outdoorMinutes, saved.hotMinutes + saved.veryHotMinutes)
        assertEquals(180, saved.veryHotMinutes)       // 11:00–14:00, nothing deducted
        assertEquals(ShiftStatus.TO_RAISE, saved.status)
        assertFalse(saved.autoEnded)
        val events = database.dao().getEvents(id)
        assertEquals(listOf(ShiftEventType.BREAK), events.map { it.type })
        assertEquals(listOf(ScheduledKind.SMOKO, ScheduledKind.LUNCH), ShiftRules.pendingScheduled(570, 720, i(t(7)), i(t(15)), events).map { it.kind })
    }

    @Test
    fun endShiftCountsNoScheduledBreakUntilItIsConfirmed() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(150)                     // 9:32
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = false)    // "Not yet": recorded, never re-proposed, counts nothing
        clock.advanceMinutes(145)                     // 11:57: a tapped break over lunch time
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(20); shifts.endBreak(owner)   // 12:17
        clock.advanceMinutes(5)                       // 12:22
        shifts.endShift(owner, hotDay)
        val events = database.dao().getEvents(id)
        assertEquals(listOf(ShiftEventType.SMOKO to true, ShiftEventType.BREAK to false), events.map { it.type to it.skipped })
        assertEquals(20, requireNotNull(database.dao().getSession(owner, id)).breakMinutes)
        // A second shift answers nothing and ends at 11:50: End shift adds no event; the passed smoko is listed as
        // unconfirmed for Fix times, and lunch, outside the shift, is not.
        clock.wall = ShiftRules.epochMillis(t(7)); clock.elapsed = 9_000L
        val second = shifts.startShift("worker-b", input(start = t(7), end = t(15)))
        clock.advanceMinutes(290)                     // 11:50
        shifts.endShift("worker-b", hotDay)
        assertTrue(database.dao().getEvents(second).isEmpty())
        val saved = requireNotNull(database.dao().getSession("worker-b", second))
        assertEquals(290, saved.outdoorMinutes); assertEquals(0, saved.breakMinutes)
        assertEquals(listOf(ScheduledKind.SMOKO), ShiftRules.pendingScheduled(570, 720, i(t(7)), i(t(11, 50)), emptyList()).map { it.kind })
    }

    @Test
    fun aScheduledConfirmationCannotOverlapABreakStartedAfterTheProposalWasShown() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(150)                     // 9:32; the proposed smoko is 9:30–9:50.
        val breakId = shifts.startBreak(owner, BreakReason.SHADE, noContext)
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        assertEquals(listOf(breakId), database.dao().getEvents(id).map { it.id })
        clock.advanceMinutes(13)
        shifts.endBreak(owner)
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        assertEquals(listOf(breakId), database.dao().getEvents(id).map { it.id })
        clock.advanceMinutes(15)
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        val events = database.dao().getEvents(id)
        val edit = fixTimesEdit(saved, events, date, minuteOf(requireNotNull(saved.startedAt)), minuteOf(requireNotNull(saved.endedAt)),
            events.toBreakRows(saved, 570, 720), null)
        assertTrue(requireNotNull(edit.validation(saved, events, Instant.ofEpochMilli(clock.wall))).ok)
        assertEquals(13, saved.breakMinutes)
    }

    @Test
    fun aScheduledConfirmationCanTouchButCannotOverlapAnotherScheduledBreak() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        database.dao().savePreferences(UserPreferences(ownerId = owner, smokoMinute = 11 * 60 + 45, lunchMinute = 12 * 60))
        clock.advanceMinutes(283)                     // 11:45; smoko ends at 12:05.
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        shifts.confirmScheduled(owner, ScheduledKind.LUNCH, taken = true)
        assertEquals(listOf(ShiftEventType.SMOKO), database.dao().getEvents(id).map { it.type })
        database.dao().savePreferences(UserPreferences(ownerId = owner, smokoMinute = 11 * 60 + 45, lunchMinute = 12 * 60 + 5))
        shifts.confirmScheduled(owner, ScheduledKind.LUNCH, taken = true)
        assertEquals(listOf(ShiftEventType.SMOKO, ShiftEventType.LUNCH), database.dao().getEvents(id).map { it.type })
    }

    @Test
    fun aSmokoOrLunchIsAnsweredOnceAndASecondTapWritesNothing() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(148)                     // 9:30
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)     // a double tap on "Yes"
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = false)    // a late "Not yet" does not undo it
        assertEquals(listOf(ShiftEventType.SMOKO to false), database.dao().getEvents(id).map { it.type to it.skipped })
        clock.advanceMinutes(150)                     // 12:00
        shifts.confirmScheduled(owner, ScheduledKind.LUNCH, taken = false)
        shifts.confirmScheduled(owner, ScheduledKind.LUNCH, taken = true)
        assertEquals(listOf(ShiftEventType.SMOKO to false, ShiftEventType.LUNCH to true), database.dao().getEvents(id).map { it.type to it.skipped })
    }

    @Test
    fun autoEndClosesAtThePlannedFinishTwoHoursLate() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(9 * 60 + 57)             // 16:59
        assertNull(shifts.autoEndIfOverdue(owner, hotDay))
        clock.advanceMinutes(1)                       // 17:00
        assertEquals(i(t(15)), shifts.autoEndIfOverdue(owner, hotDay))
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(t(15)), saved.endedAt)
        assertTrue(saved.autoEnded)
        assertNull(shifts.autoEndIfOverdue(owner, hotDay))
    }

    @Test
    fun autoEndCalledTwiceAtOnceEndsTheShiftOnceAndTheSecondCallFindsNothingToEnd() = runBlocking {
        // The ticker and the open-shift collector can both find the shift overdue as the app reopens.
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(10 * 60 - 2)             // 17:00
        val results = coroutineScope { List(2) { async { shifts.autoEndIfOverdue(owner, hotDay) } }.awaitAll() }
        assertEquals(listOf(i(t(15))), results.filterNotNull())
        assertEquals(ShiftRules.epochMillis(t(15)), requireNotNull(database.dao().getSession(owner, id)).endedAt)
    }

    // A gardener who works over and takes a break after the planned finish is still on shift: auto-end counts from the
    // last thing logged, and keeps the work and the break.
    @Test
    fun aBreakAfterThePlannedFinishMovesTheAutomaticEndToIt() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(8 * 60 + 38)             // 15:40
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(15); shifts.endBreak(owner)   // 15:55
        clock.advanceMinutes(65)                      // 17:00, two hours after the planned finish
        assertNull(shifts.autoEndIfOverdue(owner, hotDay))
        assertNotNull(openShift())
        clock.advanceMinutes(54)                      // 17:54
        assertNull(shifts.autoEndIfOverdue(owner, hotDay))
        clock.advanceMinutes(1)                       // 17:55, two hours after the break ended
        assertEquals(i(t(15, 55)), shifts.autoEndIfOverdue(owner, hotDay))
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(t(15, 55)), saved.endedAt); assertTrue(saved.autoEnded)
        assertEquals(15, saved.breakMinutes); assertEquals(8 * 60 + 55 - 15, saved.outdoorMinutes)
    }

    // "Yes" on smoko writes its 9:30–9:50 span, still running at 9:35: that is a break, so Start break is refused.
    @Test
    fun noBreakStartsOverAConfirmedSmokoStillRunning() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(150)                     // 9:32
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        clock.advanceMinutes(3)                       // 9:35
        expectFailure(ShiftError.BREAK_OPEN) { shifts.startBreak(owner, BreakReason.SHADE, noContext) }
        clock.advanceMinutes(15)                      // 9:50, the smoko is over
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        assertEquals(listOf(ShiftEventType.SMOKO, ShiftEventType.BREAK), database.dao().getEvents(id).map { it.type })
    }

    // End shift past the planned finish, with the instants carrying seconds: every worked minute has a level from the
    // hours after the finish too, and the levels add up to the minutes worked.
    @Test
    fun endShiftInOvertimeStoresALevelForEveryWorkedMinute() = runBlocking {
        val throughFour = hotDay.filter { it.time.hour in 7..16 }
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(9 * 60 + 28); clock.wall += 30_000L; clock.elapsed += 30_000L   // 16:30:30
        shifts.endShift(owner, throughFour)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(570, saved.outdoorMinutes)
        assertEquals(saved.outdoorMinutes, saved.normalMinutes + saved.warmMinutes + saved.hotMinutes + saved.veryHotMinutes + saved.extremeMinutes)
        assertEquals(570, saved.longestStretchMinutes)
        assertEquals(180, saved.veryHotMinutes); assertEquals(390, saved.hotMinutes)
    }

    // "Ended automatically" describes the finish the app set. A corrected finish is the gardener's; a note alone keeps it.
    @Test
    fun fixTimesKeepsEndedAutomaticallyOnlyForTheFinishTheAppSet() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(10 * 60 - 2)             // 17:00
        assertEquals(i(t(15)), shifts.autoEndIfOverdue(owner, hotDay))
        shifts.editTimes(owner, id, ShiftTimesEdit(t(7), t(15), emptyList(), note = "Forgot to end it"), hotDay)
        assertTrue(requireNotNull(database.dao().getSession(owner, id)).autoEnded)
        shifts.editTimes(owner, id, ShiftTimesEdit(t(7), t(14), emptyList(), note = "Forgot to end it"), hotDay)
        val corrected = requireNotNull(database.dao().getSession(owner, id))
        assertFalse(corrected.autoEnded); assertEquals(ShiftRules.epochMillis(t(14)), corrected.endedAt)
    }

    // The log never holds a shift longer than Start shift and Fix times allow, so Fix times can always save the row.
    @Test
    fun endShiftCutsAShiftLongerThanSixteenHours() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(17 * 60 - 2)             // 24:00, 17 hours after the start
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(t(23)), saved.endedAt)
        assertTrue(saved.autoEnded)
        assertEquals(16 * 60, saved.outdoorMinutes)
        assertTrue(ShiftRules.validateTimes(t(7), t(23), emptyList(), i(date.plusDays(1).atTime(1, 0))).ok)
    }

    // The same hours are never logged twice: not by Add a past shift, not by Fix times, not over the running shift.
    @Test
    fun aShiftThatOverlapsAnotherInTheLogIsRefused() = runBlocking {
        val tracked = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(8 * 60 - 2); shifts.endShift(owner, hotDay)   // 7:00–15:00
        clock.advanceMinutes(60)                      // 16:00
        val draft = PastShiftDraft("Royal Park", "Weeding & mulching", heavyWork = false, heavyClothing = false,
            start = t(14), end = t(15, 30), breaks = emptyList(), note = null)
        expectFailure(ShiftError.OVERLAPS) { shifts.addPastShift(owner, draft) }
        val later = shifts.addPastShift(owner, draft.copy(start = t(15), end = t(15, 45)))   // ends exactly as the next starts: no overlap
        expectFailure(ShiftError.OVERLAPS) { shifts.editTimes(owner, later, ShiftTimesEdit(t(14, 50), t(15, 45), emptyList(), null), emptyList()) }
        // A shift's own times never overlap itself; another owner's shifts are not this log.
        shifts.editTimes(owner, tracked, ShiftTimesEdit(t(7, 15), t(15), emptyList(), null), emptyList())
        shifts.addPastShift("worker-b", draft)
        // The running shift counts too.
        shifts.startShift(owner, input(start = t(15, 50), end = t(18)))
        expectFailure(ShiftError.OVERLAPS) { shifts.addPastShift(owner, draft.copy(start = t(15, 45), end = t(15, 55))) }
    }

    // Astra M2 #7: "Started earlier?" cannot reach back over a shift already in the log, inside the start's own transaction.
    @Test
    fun startingEarlierOverAShiftInTheLogIsRefused() = runBlocking {
        shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(8 * 60 - 2); shifts.endShift(owner, hotDay)   // 7:00–15:00
        clock.advanceMinutes(60)                                            // 16:00
        expectFailure(ShiftError.OVERLAPS) { shifts.startShift(owner, input(start = t(14), end = t(18))) }
        assertNull(database.dao().getOpenShift(owner))
        // Starting exactly as the last one ended is no overlap; a second start while it runs is SHIFT_OPEN, as before.
        shifts.startShift(owner, input(start = t(15), end = t(18)))
        expectFailure(ShiftError.SHIFT_OPEN) { shifts.startShift(owner, input(start = t(15, 30), end = t(18))) }
        // Another owner's log is not this one.
        shifts.startShift("worker-b", input(start = t(14), end = t(18)))
        assertEquals(1, database.dao().observeSessions("worker-b").first().size)
    }

    // Astra M2 #8: "Yes" on smoko writes 9:30–9:50. Ended at 9:35, the shift keeps the five minutes inside it, so Fix
    // times opens on valid rows and a note alone saves.
    @Test
    fun endShiftCutsABreakStillRunningToTheShiftsEnd() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(150)                     // 9:32
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        clock.advanceMinutes(3)                       // 9:35
        shifts.endShift(owner, hotDay)
        val smoko = database.dao().getEvents(id).single()
        assertEquals(ShiftRules.epochMillis(t(9, 30)) to ShiftRules.epochMillis(t(9, 35)), smoko.startedAt to smoko.endedAt)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(5, saved.breakMinutes); assertEquals(150, saved.outdoorMinutes)
        val edit = fixTimesAsOpened(saved, note = "Called off at 9:35")
        assertTrue(ShiftRules.validateTimes(t(7), t(9, 35), edit.breaks.filter { it.taken }.map { BreakTimes(it.key, it.kind, it.start, it.end) }, i(t(9, 40))).ok)
        shifts.editTimes(owner, id, edit, hotDay)
        assertEquals("Called off at 9:35", requireNotNull(database.dao().getSession(owner, id)).note)
    }

    // Astra M2 fix round 1, F2: "I don't feel right" at 15:20:10 and End shift at 15:20:50 leave a 40-second break that
    // Fix times shows as 15:20–15:20. Untouched, that row is the stored one, so Fix times opens valid, a note saves, and
    // the break (the gardener's record that they felt unwell) keeps its stored times.
    @Test
    fun aBreakCutToUnderAMinuteStillLetsFixTimesSaveANote() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(8 * 60 + 18); clock.wall += 10_000L; clock.elapsed += 10_000L   // 15:20:10
        shifts.startBreak(owner, BreakReason.UNWELL, noContext)
        clock.wall += 40_000L; clock.elapsed += 40_000L                                      // 15:20:50
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        val unwell = database.dao().getEvents(id).single()
        assertEquals(BreakReason.UNWELL, unwell.reason)
        assertEquals(0, saved.breakMinutes); assertEquals(8 * 60 + 20, saved.outdoorMinutes)
        shifts.editTimes(owner, id, fixTimesAsOpened(saved, note = "Went home unwell"), hotDay)
        val after = requireNotNull(database.dao().getSession(owner, id))
        assertEquals("Went home unwell", after.note)
        assertEquals(saved.startedAt to saved.endedAt, after.startedAt to after.endedAt)
        assertEquals(unwell, database.dao().getEvents(id).single())
        assertEquals(saved.breakMinutes to saved.outdoorMinutes, after.breakMinutes to after.outdoorMinutes)
    }

    // Astra M2 fix round 1, F3/M1: on 5 Apr 2026 02:00–03:00 happens twice. A 22:00 shift ended at the repeated 02:50,
    // with a break in the repeated hour. Fix times shows wall-clock minutes; a note-only save keeps every stored instant,
    // so the end and the break do not move an hour back and the totals stay.
    @Test
    fun aNoteOnlySaveOnTheAutumnNightMovesNoTime() = runBlocking {
        val autumn = LocalDate.of(2026, 4, 5)
        clock.wall = ShiftRules.epochMillis(autumn.minusDays(1).atTime(22, 0))
        val id = shifts.startShift(owner, input(start = autumn.minusDays(1).atTime(22, 0), end = autumn.atTime(6, 0)))
        clock.advanceMinutes(320)                                    // the repeated 02:20 (+10)
        shifts.startBreak(owner, BreakReason.SHADE, noContext)
        clock.advanceMinutes(10); shifts.endBreak(owner)             // the repeated 02:30
        clock.advanceMinutes(20); shifts.endShift(owner, emptyList()) // the repeated 02:50
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(350 - 10, saved.outdoorMinutes); assertEquals(10, saved.breakMinutes)
        val stored = database.dao().getEvents(id).single()
        shifts.editTimes(owner, id, fixTimesAsOpened(saved, note = "Clocks went back"), emptyList())
        val after = requireNotNull(database.dao().getSession(owner, id))
        assertEquals("Clocks went back", after.note)
        assertEquals(saved.startedAt to saved.endedAt, after.startedAt to after.endedAt)
        assertEquals(saved.outdoorMinutes to saved.breakMinutes, after.outdoorMinutes to after.breakMinutes)
        assertEquals(stored.startedAt to stored.endedAt, database.dao().getEvents(id).single().let { it.startedAt to it.endedAt })
    }

    // Astra M2 #8: the 16-hour cut at 23:00 drops what came after it (a break, an answer) and cuts the break that ran
    // over it; an answer inside the shift stays.
    @Test
    fun theSixteenHourCutDropsWhatCameAfterItAndCutsWhatRanOverIt() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(15 * 60 + 43)            // 22:45
        shifts.cantStop(owner, noContext)
        clock.advanceMinutes(5); shifts.startBreak(owner, BreakReason.SHADE, noContext)   // 22:50
        clock.advanceMinutes(20); shifts.endBreak(owner)                                    // 23:10, over the 23:00 cut
        clock.advanceMinutes(20); shifts.cantStop(owner, noContext)                         // 23:30, after it
        clock.advanceMinutes(10); shifts.startBreak(owner, BreakReason.SHADE, noContext)   // 23:40, after it, still open
        clock.advanceMinutes(20)                                                            // 24:00
        shifts.endShift(owner, hotDay)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(t(23)), saved.endedAt); assertTrue(saved.autoEnded)
        val events = database.dao().getEvents(id)
        assertEquals(listOf(ShiftEventType.CANT_STOP, ShiftEventType.BREAK), events.map { it.type })
        assertEquals(ShiftRules.epochMillis(t(22, 45)), events[0].startedAt)
        assertEquals(ShiftRules.epochMillis(t(22, 50)) to ShiftRules.epochMillis(t(23)), events[1].startedAt to events[1].endedAt)
        assertEquals(10, saved.breakMinutes); assertEquals(16 * 60 - 10, saved.outdoorMinutes)
        shifts.editTimes(owner, id, fixTimesAsOpened(saved, note = "Forgot to end it"), hotDay)
        assertEquals(10, requireNotNull(database.dao().getSession(owner, id)).breakMinutes)
    }

    // Astra M2 #5: on a 22:00–10:00 shift the 9:30 smoko is tomorrow's, so "Yes" writes tomorrow's 9:30–9:50.
    @Test
    fun aNightShiftConfirmsTomorrowsSmoko() = runBlocking {
        val tomorrow = date.plusDays(1)
        clock.wall = ShiftRules.epochMillis(t(22))
        val id = shifts.startShift(owner, input(start = t(22), end = tomorrow.atTime(10, 0)))
        clock.advanceMinutes(11 * 60 + 32)            // 9:32 tomorrow
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        shifts.confirmScheduled(owner, ScheduledKind.LUNCH, taken = false)   // 12:00 is after the planned finish: nothing to answer
        val smoko = database.dao().getEvents(id).single()
        assertEquals(ShiftEventType.SMOKO, smoko.type)
        assertEquals(ShiftRules.epochMillis(tomorrow.atTime(9, 30)) to ShiftRules.epochMillis(tomorrow.atTime(9, 50)), smoko.startedAt to smoko.endedAt)
    }

    /**
     * What Fix times saves for [shift] opened and left as it is, with [note]: its stored minutes and every break row,
     * through FixTimesScreen's own builder (fixTimesEdit), so the test cannot drift from the screen.
     */
    private suspend fun fixTimesAsOpened(shift: WorkSession, note: String): ShiftTimesEdit {
        val startedAt = requireNotNull(shift.startedAt)
        val events = database.dao().getEvents(shift.id)
        return fixTimesEdit(shift, events, ShiftRules.localTime(startedAt).toLocalDate(), minuteOf(startedAt), minuteOf(requireNotNull(shift.endedAt)),
            events.toBreakRows(shift, 570, 720), note)
    }

    @Test
    fun fixTimesUpdatesBreaksInPlaceKeepsTheirDetailsAndRejectsInvalidTimes() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(148)                     // 9:30
        shifts.confirmScheduled(owner, ScheduledKind.SMOKO, taken = true)
        clock.advanceMinutes(30)                      // 10:00
        shifts.startBreak(owner, BreakReason.NO_SHADE, noContext.copy(dueAt = i(t(10))))
        clock.advanceMinutes(15); shifts.endBreak(owner)   // 10:15
        clock.advanceMinutes(285)                     // 15:00
        shifts.endShift(owner, hotDay)
        val before = requireNotNull(database.dao().getSession(owner, id))
        val smokoId = database.dao().getEvents(id).single { it.type == ShiftEventType.SMOKO }.id
        val breakId = database.dao().getEvents(id).single { it.type == ShiftEventType.BREAK }.id
        expectFailure(ShiftError.INVALID_TIMES) {
            shifts.editTimes(owner, id, ShiftTimesEdit(t(7), t(15), listOf(BreakEdit(1, null, null, t(6, 30), t(6, 45), taken = true)), note = null), hotDay)
        }
        assertEquals(before, database.dao().getSession(owner, id))
        val edit = ShiftTimesEdit(t(6, 45), t(14, 30), listOf(
            BreakEdit(1, smokoId, ScheduledKind.SMOKO, t(9, 30), t(9, 50), taken = false),   // unticked after all: the row stays, skipped
            BreakEdit(2, breakId, null, t(10), t(10, 20), taken = true),                   // the end moves; reason, heat context and id stay
            BreakEdit(3, null, ScheduledKind.LUNCH, t(12), t(12, 30), taken = true),       // the unconfirmed lunch proposal, ticked
            BreakEdit(4, null, null, t(13), t(13, 10), taken = true)                       // a break added by hand
        ), note = "  Mower broke down at 10:40  ")
        shifts.editTimes(owner, id, edit, hotDay)
        val after = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(t(6, 45)), after.startedAt); assertEquals(ShiftRules.epochMillis(t(14, 30)), after.endedAt)
        assertEquals(20 + 30 + 10, after.breakMinutes); assertEquals(465 - 60, after.outdoorMinutes)
        assertEquals("Mower broke down at 10:40", after.note)
        val events = database.dao().getEvents(id)
        assertEquals(listOf(ShiftEventType.SMOKO to true, ShiftEventType.BREAK to false, ShiftEventType.LUNCH to false, ShiftEventType.BREAK to false),
            events.map { it.type to it.skipped })
        assertEquals(smokoId, events.first().id)
        val moved = events.single { it.id == breakId }
        assertEquals(BreakReason.NO_SHADE, moved.reason); assertEquals(ShiftRules.epochMillis(t(10, 20)), moved.endedAt)
        assertEquals(ShiftRules.epochMillis(t(10)), moved.dueAt); assertEquals(HeatLevel.HOT.name, moved.level)
        // Heat recomputed from today's hours: Very hot 11:00–14:00 (180) less the 30-minute lunch and the 10-minute break.
        assertEquals(140, after.veryHotMinutes); assertEquals(after.outdoorMinutes - 140, after.hotMinutes)
        assertEquals(before.id, after.id)
        expectFailure(ShiftError.SHIFT_GONE) { shifts.editTimes(owner, id + 99, edit, hotDay) }
        expectFailure(ShiftError.SHIFT_GONE) { shifts.editTimes("worker-b", id, edit, hotDay) }
    }

    @Test
    fun fixTimesWithoutTodaysForecastKeepsTheStoredHeatStats() = runBlocking {
        val id = shifts.startShift(owner, input(start = t(7), end = t(15)))
        clock.advanceMinutes(8 * 60 - 2)              // 15:00 (the clock reads 7:02 when the 7:00 shift is started)
        shifts.endShift(owner, hotDay)
        val before = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(180, before.veryHotMinutes); assertEquals(300, before.hotMinutes)
        // No hourly data for the shift's day (a past day, or the cache moved on): times and breaks change, heat does not.
        shifts.editTimes(owner, id, ShiftTimesEdit(t(7), t(14), emptyList(), note = "Left early"), emptyList())
        val after = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(420, after.outdoorMinutes); assertEquals(0, after.breakMinutes); assertEquals(420, after.longestStretchMinutes)
        assertEquals("Left early", after.note)
        assertEquals(36.0, requireNotNull(after.peakHeat), 0.0); assertEquals(HeatLevel.VERY_HOT.name, after.peakLevel)
        assertEquals(180, after.veryHotMinutes); assertEquals(300, after.hotMinutes)
    }

    @Test
    fun legacyRowKeepsItsTypedMinutesUntilBothTimesAreEnteredAndConfirmed() = runBlocking {
        val yesterday = date.minusDays(1)
        val id = database.dao().saveSession(WorkSession(ownerId = owner, title = "Mowing", site = "Royal Park", date = "14 Jan 2026",
            outdoorMinutes = 95, breakMinutes = 15, status = ShiftStatus.NONE)).toInt()
        // A note alone: the typed minutes stay and no time is invented.
        shifts.editTimes(owner, id, ShiftTimesEdit(null, null, emptyList(), note = "Hot afternoon"), emptyList())
        val noted = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(95, noted.outdoorMinutes); assertEquals(15, noted.breakMinutes); assertEquals("Hot afternoon", noted.note)
        assertNull(noted.startedAt); assertNull(noted.endedAt)
        // One time is not a shift.
        expectFailure(ShiftError.INVALID_TIMES) { shifts.editTimes(owner, id, ShiftTimesEdit(yesterday.atTime(6, 30), null, emptyList(), null), emptyList()) }
        // Both times entered and confirmed: the row joins the measured model and its minutes are derived from then on.
        shifts.editTimes(owner, id, ShiftTimesEdit(yesterday.atTime(6, 30), yesterday.atTime(14, 30),
            listOf(BreakEdit(1, null, ScheduledKind.SMOKO, yesterday.atTime(9, 30), yesterday.atTime(9, 50), taken = true)), note = "Hot afternoon"), emptyList())
        val measured = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftRules.epochMillis(yesterday.atTime(6, 30)), measured.startedAt)
        assertEquals(20, measured.breakMinutes); assertEquals(480 - 20, measured.outdoorMinutes); assertEquals(ShiftSource.MANUAL, measured.source)
        assertEquals(1, database.dao().getEvents(id).size)
        // Once measured, blank times are refused.
        expectFailure(ShiftError.INVALID_TIMES) { shifts.editTimes(owner, id, ShiftTimesEdit(null, null, emptyList(), null), emptyList()) }
    }

    @Test
    fun addPastShiftDerivesMinutesFromPickersAndRejectsTheFuture() = runBlocking {
        val yesterday = date.minusDays(1)
        val draft = PastShiftDraft("Royal Park", "Weeding & mulching", heavyWork = false, heavyClothing = true,
            start = yesterday.atTime(6, 30), end = yesterday.atTime(14, 30),
            breaks = listOf(BreakTimes(1, ScheduledKind.SMOKO, yesterday.atTime(9, 30), yesterday.atTime(9, 50)), BreakTimes(2, null, yesterday.atTime(12, 0), yesterday.atTime(12, 45))),
            note = null)
        val id = shifts.addPastShift(owner, draft)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftSource.MANUAL, saved.source); assertEquals("14 Jan 2026", saved.date)
        assertEquals(65, saved.breakMinutes); assertEquals(480 - 65, saved.outdoorMinutes)
        assertNull(saved.peakLevel); assertTrue(saved.heavyClothing); assertFalse(saved.heavyWork)
        assertEquals(2, database.dao().getEvents(id).size)
        expectFailure(ShiftError.IN_FUTURE) { shifts.addPastShift(owner, draft.copy(start = t(7), end = t(16))) }
        expectFailure(ShiftError.INVALID_TIMES) { shifts.addPastShift(owner, draft.copy(breaks = listOf(BreakTimes(1, null, yesterday.atTime(5, 0), yesterday.atTime(5, 10))))) }
        assertEquals(1, database.dao().observeSessions(owner).first().size)
    }

    @Test
    fun previewShiftIsSavedLabelledAndBlockedWhileARealShiftIsOpen() = runBlocking {
        val previewDay = LocalDate.of(2026, 1, 9)
        val result = PreviewShiftResult("Digging & planting", heavyWork = true, start = previewDay.atTime(9, 52), end = previewDay.atTime(11, 30),
            events = listOf(ShiftEvent(shiftId = 0, type = ShiftEventType.BREAK, startedAt = ShiftRules.epochMillis(previewDay.atTime(10, 30)),
                endedAt = ShiftRules.epochMillis(previewDay.atTime(10, 45)), reason = BreakReason.SHADE)), hours = emptyList())
        shifts.startShift(owner, input())
        expectFailure(ShiftError.SHIFT_OPEN) { shifts.savePreviewShift(owner, result) }
        clock.advanceMinutes(60); shifts.endShift(owner, hotDay)
        val id = shifts.savePreviewShift(owner, result)
        val saved = requireNotNull(database.dao().getSession(owner, id))
        assertEquals(ShiftSource.PREVIEW, saved.source); assertEquals("Birrarung Marr", saved.site); assertEquals("9 Jan 2026", saved.date)
        assertEquals(15, saved.breakMinutes); assertEquals(98 - 15, saved.outdoorMinutes)
        assertEquals(id, database.dao().getEvents(id).single().shiftId)
        shifts.deleteShift(owner, id)
        assertTrue(database.dao().getEvents(id).isEmpty())
        expectFailure(ShiftError.SHIFT_GONE) { shifts.deleteShift(owner, id) }
        assertNotNull(database.dao().getSession(owner, 1))
    }
}
