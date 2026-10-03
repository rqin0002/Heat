package heatshield

import heatshield.data.AlertReason
import heatshield.data.BreakReason
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.BreakSpan
import heatshield.domain.BreakTimes
import heatshield.domain.Cadence
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.OpenAnswerKind
import heatshield.domain.OpenShift
import heatshield.domain.PersonalFactors
import heatshield.domain.ScheduledBreak
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.TimesError
import heatshield.domain.WorkClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app timer rules of spec §5.10 and §10 M2, on a fixed day with no Android clock. */
class ShiftRulesTest {
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private val shiftStart: LocalDateTime = date.atTime(7, 0)
    private val shiftEnd: LocalDateTime = date.atTime(15, 0)
    private fun t(hour: Int, minute: Int = 0) = date.atTime(hour, minute)
    private fun ms(time: LocalDateTime) = ShiftRules.epochMillis(time)

    /** Dry, windy air so H equals air; one HourHeat per hour 7..14 with [airByHour] °C (missing hours default to 26°, Normal). */
    private fun hours(vararg airByHour: Pair<Int, Double>, factors: PersonalFactors = PersonalFactors()): List<HourHeat> {
        val air = airByHour.toMap()
        return (7..14).map { h ->
            val a = air[h] ?: 26.0
            val base = HeatRules.baseLevel(a)
            HourHeat(t(h), a, 10.0, 8.0, a - 3.0, a, base, HeatRules.effectiveLevel(base, factors), HeatRules.wholeDegrees(a) >= 35, 5.0, 5.0)
        }
    }
    private val warmDay = hours(*(7..14).map { it to 30.0 }.toTypedArray())    // Warm: 60 work / 10 break
    private val hotDay = hours(*(7..14).map { it to 33.0 }.toTypedArray())     // Hot: 45 / 15

    private fun brk(start: LocalDateTime, end: LocalDateTime?, type: String = ShiftEventType.BREAK, reason: String? = BreakReason.SHADE, skipped: Boolean = false) =
        ShiftEvent(id = 0, shiftId = 1, type = type, startedAt = ms(start), endedAt = end?.let(::ms), reason = reason, skipped = skipped)
    private fun cantStop(at: LocalDateTime) = ShiftEvent(shiftId = 1, type = ShiftEventType.CANT_STOP, startedAt = ms(at), endedAt = ms(at))
    /** The shift's own timeline is real instants; [t] times on this day (no daylight-saving change) convert one to one. */
    private fun i(time: LocalDateTime): Instant = ShiftRules.instant(time)
    private fun clock(now: LocalDateTime, vararg events: ShiftEvent) = ShiftRules.workClock(i(shiftStart), events.toList(), i(now))
    private fun due(now: LocalDateTime, hours: List<HourHeat>, vararg events: ShiftEvent, rule: BreakRule = BreakRule.HEATSHIELD,
                    pending: List<ScheduledBreak> = emptyList(), snooze: LocalDateTime? = null): BreakDue =
        ShiftRules.nextBreakDue(clock(now, *events), hours, rule, pending, snooze?.let(::i), i(shiftEnd))
    private val row = WorkSession(id = 1, ownerId = "local", title = "Push mowing", site = "Birrarung Marr", date = "15 Jan 2026",
        outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ms(shiftStart), plannedEndAt = ms(shiftEnd),
        source = ShiftSource.TRACKED, heavyWork = true)

    // Work clock: a 10-minute confirmed break resets, a shorter one pauses, a scheduled time alone does nothing.
    @Test
    fun tenMinuteBreakResetsTheClockAndFiveMinutePausesIt() {
        val fresh = clock(t(8))
        assertEquals(i(shiftStart), fresh.since); assertEquals(60, fresh.workedMinutes); assertTrue(fresh.pauses.isEmpty())
        val reset = clock(t(9), brk(t(8), t(8, 10)))
        assertEquals(i(t(8, 10)), reset.since); assertEquals(50, reset.workedMinutes)
        val paused = clock(t(9), brk(t(8), t(8, 5)))
        assertEquals(i(shiftStart), paused.since); assertEquals(1, paused.pauses.size); assertEquals(115, paused.workedMinutes)
        // Smoko confirmed at 9:30–9:50 resets like any 20-minute break; a skipped smoko counts for nothing.
        assertEquals(i(t(9, 50)), clock(t(10), brk(t(9, 30), t(9, 50), type = ShiftEventType.SMOKO, reason = null)).since)
        assertEquals(i(shiftStart), clock(t(10), brk(t(9, 30), t(9, 30), type = ShiftEventType.SMOKO, reason = null, skipped = true)).since)
        // An open break is not confirmed yet: it neither resets nor pauses until it closes.
        assertEquals(i(shiftStart), clock(t(9), brk(t(8, 30), null)).since)
        // A "Can't stop yet" answer adds 0 break minutes.
        assertEquals(120, clock(t(9), cantStop(t(8, 30))).workedMinutes)
    }

    // Break due drains a budget at the hour's cadence, so rising heat brings it forward.
    @Test
    fun breakDueFollowsTheCadenceOfEachHourWalkedThrough() {
        assertEquals(BreakDue.At(i(t(8)), HeatLevel.WARM, Cadence(60, 10)), due(t(7, 5), warmDay))
        assertEquals(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, Cadence(45, 15)), due(t(7, 5), hotDay))
        // Warm until 8:00, Hot from 8:00: 30 min at 1/60 spends half, then 22.5 min at 1/45 → due 8:23 (budget full in the 23rd minute).
        val rising = hours(7 to 30.0, 8 to 33.0, 9 to 33.0, 10 to 33.0, 11 to 33.0, 12 to 33.0, 13 to 33.0, 14 to 33.0)
        val clockFrom730 = ShiftRules.workClock(i(t(7, 30)), emptyList(), i(t(7, 35)))
        assertEquals(i(t(8, 23)), ShiftRules.nextBreakDue(clockFrom730, rising, BreakRule.HEATSHIELD, emptyList(), null, i(shiftEnd)).dueAt)
        // Normal hours drain nothing: a cool shift has no heat break due.
        assertEquals(BreakDue.NoneToday, due(t(7, 5), hours()))
        // Normal until 10, Warm from 10: the clock is quiet until 10, then 60 minutes.
        assertEquals(i(t(11)), due(t(7, 5), hours(10 to 30.0, 11 to 30.0, 12 to 30.0, 13 to 30.0, 14 to 30.0)).dueAt)
        // After a reset at 9:10 on a Hot day the next break is 45 minutes later.
        assertEquals(i(t(9, 55)), due(t(9, 20), hotDay, brk(t(9), t(9, 10))).dueAt)
        // A 5-minute pause at 7:20 does not drain: due moves from 7:45 to 7:50.
        assertEquals(i(t(7, 50)), due(t(7, 30), hotDay, brk(t(7, 20), t(7, 25))).dueAt)
    }

    @Test
    fun extremeHourStopsTheWalkAndTheUnionTableStopsWorkFromThirtySix() {
        val extremeFromNine = hours(7 to 33.0, 8 to 33.0, 9 to 39.0, 10 to 39.0, 11 to 39.0, 12 to 39.0, 13 to 39.0, 14 to 39.0)
        // Hot at 7 drains the budget by 7:45 first.
        assertEquals(i(t(7, 45)), due(t(7, 5), extremeFromNine).dueAt)
        // From a reset at 8:30 the budget is not full when 9:00 turns Extreme: the stop line wins.
        assertEquals(BreakDue.Stop(i(t(9)), BreakRule.HEATSHIELD), due(t(8, 40), extremeFromNine, brk(t(8, 20), t(8, 30))))
        // Heavy work and heavy clothing take a Very hot hour to Extreme.
        val bumped = hours(*(7..14).map { it to 36.0 }.toTypedArray(), factors = PersonalFactors(heavyWork = true, heavyClothing = true))
        assertEquals(BreakDue.Stop(i(shiftStart), BreakRule.HEATSHIELD), due(t(7, 5), bumped))
        // Union table, adapted by HeatShield: a heat number of 36° is "no work" from the minute it applies, whatever the personal factors.
        val union = due(t(7, 5), bumped, rule = BreakRule.UNION_VTHC)
        assertEquals(BreakDue.Stop(i(shiftStart), BreakRule.UNION_VTHC), union)
        // Neither "Can't stop yet" nor a smoko ten minutes away defers the stop.
        assertEquals(union, due(t(7, 5), bumped, cantStop(t(7, 1)), rule = BreakRule.UNION_VTHC,
            pending = listOf(ScheduledBreak(ScheduledKind.SMOKO, i(t(7, 10)))), snooze = t(7, 11)))
        // Below the stop the rows drive the same budget: 30° is 45/15 (due 7:45), 33° is 30/30 (due 7:30).
        assertEquals(BreakDue.At(i(t(7, 45)), HeatLevel.WARM, Cadence(45, 15)), due(t(7, 5), warmDay, rule = BreakRule.UNION_VTHC))
        assertEquals(i(t(7, 30)), due(t(7, 5), hotDay, rule = BreakRule.UNION_VTHC).dueAt)
    }

    @Test
    fun scheduledBreakWithinFifteenMinutesCoversTheDueBreakAndSnoozeDelaysIt() {
        val smoko = ScheduledBreak(ScheduledKind.SMOKO, i(t(9, 30)))
        // Due 9:15 on a Hot day from a reset at 8:30 → inside the guard → smoko covers it.
        val covered = due(t(8, 40), hotDay, brk(t(8, 20), t(8, 30)), pending = listOf(smoko))
        assertEquals(BreakDue.At(i(t(9, 30)), HeatLevel.HOT, Cadence(45, 15), scheduled = ScheduledKind.SMOKO), covered)
        // Due 9:14 is one minute outside the guard: it stands.
        assertEquals(i(t(9, 14)), due(t(8, 40), hotDay, brk(t(8, 19), t(8, 29)), pending = listOf(smoko)).dueAt)
        // A skipped smoko is not pending, so nothing covers 9:15.
        assertEquals(i(t(9, 15)), due(t(8, 40), hotDay, brk(t(8, 20), t(8, 30)), pending = emptyList()).dueAt)
        // Can't stop yet at 7:40 (due 7:45) → reminder at 7:50.
        val snoozed = due(t(7, 41), hotDay, cantStop(t(7, 40)), snooze = t(7, 50))
        assertEquals(BreakDue.At(i(t(7, 50)), HeatLevel.HOT, Cadence(45, 15), snoozed = true), snoozed)
        assertEquals(i(t(7, 50)), ShiftRules.snoozedUntil(listOf(cantStop(t(7, 40)))))
        assertNull(ShiftRules.snoozedUntil(listOf(brk(t(7, 20), t(7, 30)))))
        // Once a later break resets the clock, an old reminder time is behind the new due time and changes nothing.
        assertEquals(i(t(8, 45)), due(t(8, 10), hotDay, cantStop(t(7, 40)), brk(t(7, 50), t(8)), snooze = t(7, 50)).dueAt)
    }

    @Test
    fun aGapOrNoForecastCannotBeTimedThroughAndIsNeverReadAsCool() {
        assertEquals(BreakDue.NoForecast, due(t(7, 5), emptyList()))
        // 9:00 has no forecast. From a reset at 8:30 on a Hot day the budget would fill at 9:15, but the walk reaches the gap first.
        val gapAtNine = hotDay.filter { it.time.hour != 9 }
        assertEquals(BreakDue.NoForecast, due(t(8, 40), gapAtNine, brk(t(8, 20), t(8, 30))))
        // From a reset at 8:00 the break is due 8:45, before the gap: a known answer stays known.
        assertEquals(i(t(8, 45)), due(t(8, 10), gapAtNine, brk(t(7, 50), t(8))).dueAt)
        // Only 7:00 and 8:00 have forecasts: later minutes are unknown, not cool, so nothing says "no break due".
        assertEquals(BreakDue.NoForecast, due(t(9, 20), hotDay.take(2), brk(t(8, 50), t(9))))
    }

    @Test
    fun pendingScheduledSkipsAnsweredOrCoveredBreaksAndProposesOneAtATime() {
        val none = ShiftRules.pendingScheduled(570, 720, i(shiftStart), i(shiftEnd), emptyList())
        assertEquals(listOf(ScheduledBreak(ScheduledKind.SMOKO, i(t(9, 30))), ScheduledBreak(ScheduledKind.LUNCH, i(t(12)))), none)
        // Smoko taken or skipped is answered; a tapped break over 9:30 covers it; lunch outside the shift is never pending.
        assertEquals(listOf(ScheduledKind.LUNCH), ShiftRules.pendingScheduled(570, 720, i(shiftStart), i(shiftEnd),
            listOf(brk(t(9, 30), t(9, 50), type = ShiftEventType.SMOKO, reason = null))).map { it.kind })
        assertEquals(listOf(ScheduledKind.LUNCH), ShiftRules.pendingScheduled(570, 720, i(shiftStart), i(shiftEnd),
            listOf(brk(t(9, 30), t(9, 30), type = ShiftEventType.SMOKO, reason = null, skipped = true))).map { it.kind })
        assertEquals(listOf(ScheduledKind.LUNCH), ShiftRules.pendingScheduled(570, 720, i(shiftStart), i(shiftEnd), listOf(brk(t(9, 25), t(9, 40)))).map { it.kind })
        assertEquals(listOf(ScheduledKind.SMOKO), ShiftRules.pendingScheduled(570, 16 * 60, i(shiftStart), i(shiftEnd), emptyList()).map { it.kind })
        // The proposal is the pending break whose time has come, for 90 minutes.
        assertNull(ShiftRules.proposal(none, i(t(9, 29))))
        assertEquals(ScheduledKind.SMOKO, ShiftRules.proposal(none, i(t(9, 30)))?.kind)
        assertEquals(ScheduledKind.SMOKO, ShiftRules.proposal(none, i(t(10, 59)))?.kind)
        assertNull(ShiftRules.proposal(none, i(t(11))))
        assertEquals(ScheduledKind.LUNCH, ShiftRules.proposal(none, i(t(12, 10)))?.kind)
    }

    @Test
    fun openAnswerIsCantStopUntilABreakThenNoShadeForTheShift() {
        assertNull(ShiftRules.openAnswer(emptyList()))
        assertEquals(OpenAnswerKind.CANT_STOP, ShiftRules.openAnswer(listOf(cantStop(t(8)))))
        assertNull(ShiftRules.openAnswer(listOf(cantStop(t(8)), brk(t(8, 20), t(8, 30)))))
        assertEquals(OpenAnswerKind.CANT_STOP, ShiftRules.openAnswer(listOf(brk(t(7, 20), t(7, 30)), cantStop(t(8)))))
        assertEquals(OpenAnswerKind.NO_SHADE, ShiftRules.openAnswer(listOf(brk(t(8), t(8, 15), reason = BreakReason.NO_SHADE))))
        assertEquals(OpenAnswerKind.CANT_STOP, ShiftRules.openAnswer(listOf(brk(t(8), t(8, 15), reason = BreakReason.NO_SHADE), cantStop(t(9)))))
    }

    @Test
    fun alertRowsChangeNothingTheClockOrAnswersCompute() {
        val alert = ShiftEvent(shiftId = 1, type = ShiftEventType.ALERT, startedAt = ms(t(8, 45)), endedAt = ms(t(8, 45)),
            reason = AlertReason.BREAK_DUE, level = HeatLevel.HOT.name, heatC = 33.0, dueAt = ms(t(8, 45)))
        val events = listOf(cantStop(t(8)), alert, brk(t(8, 50), t(9, 2)))
        // The 12-minute break resets the clock; the alert is neither a break nor a pause.
        assertEquals(WorkClock(i(t(9, 2)), emptyList(), 8), clock(t(9, 10), *events.toTypedArray()))
        assertEquals(listOf(BreakSpan(i(t(8, 50)), i(t(9, 2)))), ShiftRules.confirmedBreaks(events))
        // "Can't stop yet" stands until a break follows it; an alert row posted after it is not that break.
        assertEquals(OpenAnswerKind.CANT_STOP, ShiftRules.openAnswer(listOf(cantStop(t(8)), alert)))
        assertNull(ShiftRules.openAnswer(events))
        val open = OpenShift(row, listOf(alert))
        assertNull(ShiftRules.evaluate(open, hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(8, 46))).openBreak)
        assertEquals(2, ShiftRules.pendingScheduled(570, 720, i(t(7)), i(t(15)), listOf(alert)).size)
        assertEquals(0, ShiftRules.summarise(i(t(7)), i(t(15)), listOf(alert), hotDay).breakMinutes)
        // An alert inside smoko is still not a taken break, and app history cannot extend a forgotten shift.
        val duringSmoko = alert.copy(startedAt = ms(t(9, 35)), endedAt = ms(t(9, 35)))
        assertEquals(2, ShiftRules.pendingScheduled(570, 720, i(t(7)), i(t(15)), listOf(duringSmoko)).size)
        val afterFinish = alert.copy(startedAt = ms(t(16)), endedAt = ms(t(16)))
        assertEquals(i(t(15)), ShiftRules.autoEndAt(i(t(15)), listOf(afterFinish)))
        assertEquals(i(t(15, 20)), ShiftRules.autoEndAt(i(t(15)), listOf(cantStop(t(15, 20)), afterFinish)))
    }

    @Test
    fun evaluateCombinesClockDueProposalAndAnswerOnTheShiftTimeline() {
        val open = OpenShift(row, listOf(brk(t(8), t(8, 12)), cantStop(t(8, 50))))
        val eval = ShiftRules.evaluate(open, hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(8, 58)))
        assertEquals(i(t(8, 12)), eval.clock.since); assertEquals(46, eval.clock.workedMinutes)
        assertEquals(OpenAnswerKind.CANT_STOP, eval.openAnswer)
        assertEquals(i(t(9)), eval.snoozedUntil)
        // Due 8:57 on the Hot cadence, moved to the 9:00 reminder. 9:00 is outside the 15 minutes before smoko, so smoko does not cover it.
        assertEquals(BreakDue.At(i(t(9)), HeatLevel.HOT, Cadence(45, 15), snoozed = true), eval.due)
        assertFalse(eval.isDue)
        assertEquals(hotDay[1], eval.current)
        // The reminder time stays the due time after it passes: at 9:42 the break is 42 minutes overdue, and smoko is proposed.
        val later = ShiftRules.evaluate(open, hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(9, 42)))
        assertEquals(i(t(9)), later.due.dueAt)
        assertTrue(later.isDue); assertEquals(42, later.overdueMinutes)
        assertEquals(ScheduledKind.SMOKO, later.proposal?.kind)
        val onBreak = ShiftRules.evaluate(open.copy(events = open.events + brk(t(9, 42), null)), hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(9, 45)))
        assertEquals(t(9, 42), onBreak.openBreak?.let { ShiftRules.localTime(it.startedAt) })
        assertFalse(onBreak.isDue)
        // A shift running past its planned finish keeps counting.
        assertEquals(i(t(15, 30)), ShiftRules.evaluate(open, hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(15, 30))).shiftEnd)
    }

    // A shift keeps running past its planned finish, so it reads the forecast hours after it too (ContextReasoner.shiftHours).
    // Reset by a break ending 14:30 on a Hot day, the next break is due 15:15, past the 15:00 planned finish.
    @Test
    fun overtimeIsTimedFromTheHoursAfterThePlannedFinish() {
        val throughFour = (7..16).map { h ->
            val base = HeatRules.baseLevel(33.0)
            HourHeat(t(h), 33.0, 10.0, 8.0, 30.0, 33.0, base, base, false, 5.0, 5.0)
        }
        val open = OpenShift(row, listOf(brk(t(14, 20), t(14, 30))))
        val overtime = ShiftRules.evaluate(open, throughFour, BreakRule.HEATSHIELD, 570, 720, i(t(15, 20)))
        assertEquals(BreakDue.At(i(t(15, 15)), HeatLevel.HOT, Cadence(45, 15)), overtime.due)
        assertEquals(t(15), overtime.current?.time)
        assertTrue(overtime.isDue); assertEquals(5, overtime.overdueMinutes)
        // Hours that stop at the planned finish (Today's shift hours) cannot time it: the walk meets 15:00 with no forecast.
        val shiftHoursOnly = throughFour.filter { it.time.hour < 15 }
        val blind = ShiftRules.evaluate(open, shiftHoursOnly, BreakRule.HEATSHIELD, 570, 720, i(t(15, 20)))
        assertEquals(BreakDue.NoForecast, blind.due); assertNull(blind.current)
    }

    // "Yes" writes a smoko's whole span; once confirmed it is a break on the timeline, so a lunch it runs over is not proposed.
    @Test
    fun aConfirmedSmokoOverLunchTimeLeavesNoLunchToPropose() {
        val smoko = brk(t(11, 45), t(12, 5), type = ShiftEventType.SMOKO, reason = null)
        assertEquals(emptyList<ScheduledBreak>(), ShiftRules.pendingScheduled(705, 720, i(shiftStart), i(shiftEnd), listOf(smoko)))
        // A skipped smoko covers nothing, and a "Can't stop yet" answer never does.
        val skipped = brk(t(11, 45), t(11, 45), type = ShiftEventType.SMOKO, reason = null, skipped = true)
        assertEquals(listOf(ScheduledKind.LUNCH), ShiftRules.pendingScheduled(705, 720, i(shiftStart), i(shiftEnd), listOf(skipped, cantStop(t(12)))).map { it.kind })
        // While the smoko runs it is a break: nothing else may start over it.
        assertTrue(ShiftRules.scheduledBreakRunning(listOf(smoko), ms(t(11, 50))))
        assertFalse(ShiftRules.scheduledBreakRunning(listOf(smoko), ms(t(12, 5))))
        assertFalse(ShiftRules.scheduledBreakRunning(listOf(skipped), ms(t(11, 45))))
        assertFalse(ShiftRules.scheduledBreakRunning(listOf(brk(t(11, 45), null)), ms(t(11, 50))))   // a tapped break is guarded by the DAO
    }

    // A forgotten shift ends at its planned finish, or at the last break or answer logged after it.
    @Test
    fun autoEndIsThePlannedFinishOrTheLastEventAfterIt() {
        assertEquals(i(t(15)), ShiftRules.autoEndAt(i(t(15)), emptyList()))
        assertEquals(i(t(15)), ShiftRules.autoEndAt(i(t(15)), listOf(brk(t(10), t(10, 15)), cantStop(t(14)))))
        assertEquals(i(t(15, 55)), ShiftRules.autoEndAt(i(t(15)), listOf(brk(t(10), t(10, 15)), brk(t(15, 40), t(15, 55)))))
        // A break still open counts from its start; an answer after the finish counts too.
        assertEquals(i(t(16, 40)), ShiftRules.autoEndAt(i(t(15)), listOf(brk(t(16, 40), null))))
        assertEquals(i(t(15, 10)), ShiftRules.autoEndAt(i(t(15)), listOf(cantStop(t(15, 10)))))
    }

    // "Yes" on a smoko or lunch writes its scheduled span, so the break is usually still running when it is confirmed.
    // It counts in full from that moment: its whole length decides reset or pause, and no break falls due during it.
    @Test
    fun aConfirmedSmokoStillRunningResetsTheClockAtItsEndAndNothingFallsDueDuringIt() {
        val smoko = brk(t(9, 30), t(9, 50), type = ShiftEventType.SMOKO, reason = null)
        // From a reset at 8:45 on a Hot day the break is due 9:30. Due exactly at smoko time is outside the guard, so it
        // stands, and smoko is proposed at the same minute. Yes is tapped any time from 9:30 to 9:49.
        listOf(t(9, 30), t(9, 31), t(9, 40), t(9, 49)).forEach { now ->
            val running = clock(now, brk(t(8, 35), t(8, 45)), smoko)
            assertEquals("since at $now", i(t(9, 50)), running.since)
            assertEquals("worked at $now", 0, running.workedMinutes)
            assertTrue("pauses at $now", running.pauses.isEmpty())
        }
        val eval = ShiftRules.evaluate(OpenShift(row, listOf(brk(t(8, 35), t(8, 45)), smoko)), hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(9, 31)))
        assertEquals(i(t(9, 50)), eval.clock.since)
        assertEquals(BreakDue.At(i(t(10, 35)), HeatLevel.HOT, Cadence(45, 15)), eval.due)
        assertFalse(eval.isDue); assertEquals(0, eval.overdueMinutes)
        assertNull(eval.openBreak); assertNull(eval.proposal)
        // A due time the guard had moved onto smoko (9:15 → 9:30) stays met once smoko is answered: it never falls back to 9:15.
        val guarded = OpenShift(row, listOf(brk(t(8, 20), t(8, 30))))
        assertEquals(BreakDue.At(i(t(9, 30)), HeatLevel.HOT, Cadence(45, 15), scheduled = ScheduledKind.SMOKO),
            ShiftRules.evaluate(guarded, hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(9, 30))).due)
        val answered = ShiftRules.evaluate(guarded.copy(events = guarded.events + smoko), hotDay, BreakRule.HEATSHIELD, 570, 720, i(t(9, 32)))
        assertEquals(i(t(10, 35)), answered.due.dueAt); assertFalse(answered.isDue)
        // Once smoko ends the clock runs from 9:50.
        assertEquals(10, clock(t(10), brk(t(8, 35), t(8, 45)), smoko).workedMinutes)
        // A short confirmed break still running pauses the clock: the worked minutes stop at now, and the walk skips the
        // whole confirmed span, so its remaining minutes are never counted as work.
        val shortRunning = clock(t(8, 42), brk(t(8), t(8, 15)), brk(t(8, 40), t(8, 45)))
        assertEquals(i(t(8, 15)), shortRunning.since); assertEquals(25, shortRunning.workedMinutes)
        assertEquals(listOf(BreakSpan(i(t(8, 40)), i(t(8, 45)))), shortRunning.pauses)
        assertEquals(i(t(9, 5)), due(t(8, 42), hotDay, brk(t(8), t(8, 15)), brk(t(8, 40), t(8, 45))).dueAt)
    }

    @Test
    fun summariseDerivesEveryStoredMinuteFromEvents() {
        val events = listOf(brk(t(9, 30), t(9, 50), type = ShiftEventType.SMOKO, reason = null), brk(t(12), t(12, 30), type = ShiftEventType.LUNCH, reason = null),
            brk(t(14, 50), t(15, 10)), cantStop(t(11)), brk(t(9, 40), t(9, 45)))   // the 9:40 break lies inside smoko and merges
        val mixed = hours(7 to 26.0, 8 to 30.0, 9 to 33.0, 10 to 33.0, 11 to 36.0, 12 to 36.0, 13 to 39.0, 14 to 33.0)
        val totals = ShiftRules.summarise(i(shiftStart), i(shiftEnd), events, mixed)
        assertEquals(60, totals.breakMinutes)            // 20 + 30 + 10 (14:50–15:00 clipped at the shift end)
        assertEquals(420, totals.outdoorMinutes)         // 480 − 60
        assertEquals(t(13), totals.peak?.time)
        assertEquals(mapOf(HeatLevel.NORMAL to 60, HeatLevel.WARM to 60, HeatLevel.HOT to 150, HeatLevel.VERY_HOT to 90, HeatLevel.EXTREME to 60), totals.minutesByLevel)
        assertEquals(150, totals.longestStretchMinutes)  // 7:00–9:30
        // The peak is an hour the shift worked in: 13:00–14:00 (39°) ends as a 14:00 shift starts, so 14:00 (33°) is the peak.
        assertEquals(t(14), ShiftRules.summarise(i(t(14)), i(shiftEnd), emptyList(), mixed).peak?.time)
        val blind = ShiftRules.summarise(i(shiftStart), i(shiftEnd), events, emptyList())
        assertNull(blind.peak); assertTrue(blind.minutesByLevel.isEmpty()); assertEquals(420, blind.outdoorMinutes)
    }

    @Test
    fun validateTimesReportsFieldLevelErrors() {
        val latest = i(t(16))
        assertTrue(ShiftRules.validateTimes(shiftStart, shiftEnd, listOf(BreakTimes(1, null, t(9), t(9, 15))), latest).ok)
        assertEquals(TimesError.END_BEFORE_START, ShiftRules.validateTimes(t(9), t(9), emptyList(), latest).shift)
        assertEquals(TimesError.TOO_LONG, ShiftRules.validateTimes(t(0), t(16, 1), emptyList(), i(date.plusDays(1).atTime(0, 0))).shift)
        assertEquals(TimesError.IN_FUTURE, ShiftRules.validateTimes(shiftStart, t(16, 30), emptyList(), latest).shift)
        val breaks = listOf(BreakTimes(1, null, t(9), t(8, 50)), BreakTimes(2, null, t(6, 50), t(7, 10)), BreakTimes(3, ScheduledKind.SMOKO, t(10), t(10, 20)), BreakTimes(4, null, t(10, 10), t(10, 30)))
        val result = ShiftRules.validateTimes(shiftStart, shiftEnd, breaks, latest)
        assertNull(result.shift)
        assertEquals(mapOf(1 to TimesError.END_BEFORE_START, 2 to TimesError.OUTSIDE_SHIFT, 3 to TimesError.OVERLAPS, 4 to TimesError.OVERLAPS), result.breaks)
    }

    @Test
    fun unchangedSavedTimesRemainValidAfterTheWallClockMovesBackward() {
        val savedBreak = BreakTimes(1, null, t(8, 45), t(8, 55), i(t(8, 45)), i(t(8, 55)))
        assertTrue(ShiftRules.validateTimes(t(7), t(9), listOf(savedBreak), i(t(8, 40)), i(t(7)), i(t(9))).ok)
    }

    @Test
    fun keepingASavedFutureEndDoesNotAllowNewFutureShiftTimes() {
        val latest = i(t(8, 40))
        assertEquals(TimesError.IN_FUTURE, ShiftRules.validateTimes(t(7), t(8, 50), emptyList(), latest, i(t(7)), i(t(9))).shift)
        assertEquals(TimesError.IN_FUTURE, ShiftRules.validateTimes(t(8, 45), t(9), emptyList(), latest, i(t(7)), i(t(9))).shift)
        assertEquals(TimesError.IN_FUTURE, ShiftRules.validateTimes(t(7), t(9, 1), emptyList(), latest, i(t(7)), i(t(9))).shift)
    }

    @Test
    fun keepingASavedFutureEndDoesNotAllowNewFutureBreakTimes() {
        val savedBreak = BreakTimes(1, null, t(8, 45), t(8, 55), i(t(8, 45)), i(t(8, 55)))
        val latest = i(t(8, 40))
        val editedEnd = ShiftRules.validateTimes(t(7), t(9), listOf(savedBreak.copy(end = t(8, 50))), latest, i(t(7)), i(t(9)))
        assertEquals(mapOf(1 to TimesError.IN_FUTURE), editedEnd.breaks)
        val editedStart = ShiftRules.validateTimes(t(7), t(9), listOf(savedBreak.copy(start = t(8, 46))), latest, i(t(7)), i(t(9)))
        assertEquals(mapOf(1 to TimesError.IN_FUTURE), editedStart.breaks)
        val added = ShiftRules.validateTimes(t(7), t(9), listOf(BreakTimes(2, null, t(8, 45), t(8, 50))), latest, i(t(7)), i(t(9)))
        assertEquals(mapOf(2 to TimesError.IN_FUTURE), added.breaks)
    }

    @Test
    fun recoveryUsesMonotonicTimeOnTheSameBootAndWallTimeAfterAReboot() {
        val startedAt = ms(t(7)); val startedElapsed = 50_000L
        assertEquals(600_000L, ShiftRules.recoveredElapsedMillis(startedAt, startedElapsed, 7, startedAt + 999_999L, startedElapsed + 600_000L, 7))
        // Rebooted (boot count changed, elapsed restarted): wall time, never a cleared shift.
        assertEquals(999_999L, ShiftRules.recoveredElapsedMillis(startedAt, startedElapsed, 7, startedAt + 999_999L, 20_000L, 8))
        // Legacy anchor missing: wall time. A wall clock set back never goes negative.
        assertEquals(999_999L, ShiftRules.recoveredElapsedMillis(startedAt, null, null, startedAt + 999_999L, 20_000L, 8))
        assertEquals(0L, ShiftRules.recoveredElapsedMillis(startedAt, null, null, startedAt - 5_000L, 20_000L, 8))
    }

    @Test
    fun unionCadenceTableOnWholeDegreesOfTheHeatNumberStopsFromThirtySix() {
        assertNull(HeatRules.unionCadence(29.4))
        assertEquals(Cadence(45, 15), HeatRules.unionCadence(29.5)); assertEquals(Cadence(45, 15), HeatRules.unionCadence(31.4))
        assertEquals(Cadence(30, 30), HeatRules.unionCadence(31.5)); assertEquals(Cadence(30, 30), HeatRules.unionCadence(33.4))
        assertEquals(Cadence(15, 45), HeatRules.unionCadence(33.5)); assertEquals(Cadence(15, 45), HeatRules.unionCadence(35.4))
        // From 36° there is no row: the walk returns the stop instead.
        assertNull(HeatRules.unionCadence(35.5)); assertNull(HeatRules.unionCadence(45.0))
        assertEquals(36, HeatRules.UNION_STOP_C); assertTrue(HeatRules.wholeDegrees(35.5) >= HeatRules.UNION_STOP_C)
    }

    @Test
    fun startShiftPrefillsTheUsualFinishUntilItPassesThenTheUsualLengthOn() {
        val tomorrow = date.plusDays(1)
        // Usual day 7:00–15:00. Before the usual finish (even before the usual start), today's usual finish.
        assertEquals(t(15), ShiftRules.defaultFinish(t(10), 420, 900))
        assertEquals(t(15), ShiftRules.defaultFinish(t(5), 420, 900))
        assertEquals(t(15), ShiftRules.defaultFinish(t(14, 59), 420, 900))
        // At and after it, the usual 8 hours on, never tomorrow's 15:00 (23 hours away, past the 16-hour cap).
        assertEquals(t(23), ShiftRules.defaultFinish(t(15), 420, 900))
        assertEquals(tomorrow.atTime(0, 0), ShiftRules.defaultFinish(t(16), 420, 900))
        // Start shift works in whole minutes: under a minute left counts as passed.
        assertEquals(t(22, 59).plusSeconds(30), ShiftRules.defaultFinish(t(14, 59).plusSeconds(30), 420, 900))
        // The cap: a usual finish more than 16 hours away, and a usual day longer than 16 hours.
        assertEquals(t(17), ShiftRules.defaultFinish(t(1), 300, 1410))
        assertEquals(tomorrow.atTime(15, 45), ShiftRules.defaultFinish(t(23, 45), 300, 1410))
        // The screen's own wrap: a picked finish at or before the start is tomorrow's.
        assertEquals(t(15), ShiftRules.finishAfter(t(7, 2), 900))
        assertEquals(tomorrow.atTime(14, 0), ShiftRules.finishAfter(t(14), 840))
        assertEquals(tomorrow.atTime(6, 0), ShiftRules.finishAfter(t(22).plusSeconds(30), 360))
    }

    // Astra M2 #1: "Can't stop yet" or a covering smoko moves the due time later; every hour worked in the deferral is
    // checked again, so a 07:55 break pushed to 08:05 over an Extreme 8 am is the stop at 08:00, never a later break.
    @Test
    fun aDeferredBreakThatReachesTheStopLineIsTheStopAndAGapInItIsUnknown() {
        val reset = brk(t(7), t(7, 10))   // a 10-minute break resets the clock at 7:10; Hot 45 min → due 7:55
        val extremeFromEight = hours(7 to 33.0, *(8..14).map { it to 39.0 }.toTypedArray())
        assertEquals(i(t(7, 55)), due(t(7, 20), extremeFromEight, reset).dueAt)
        assertEquals(BreakDue.Stop(i(t(8)), BreakRule.HEATSHIELD), due(t(7, 56), extremeFromEight, reset, cantStop(t(7, 55)), snooze = t(8, 5)))
        // A smoko at 8:05 covers 7:55 (its 15-minute guard) and moves the break there: the 8 am stop comes first.
        assertEquals(BreakDue.Stop(i(t(8)), BreakRule.HEATSHIELD), due(t(7, 20), extremeFromEight, reset, pending = listOf(ScheduledBreak(ScheduledKind.SMOKO, i(t(8, 5))))))
        // Under the union table: 31° (45/15) then 36° from 8 am.
        val unionFromEight = hours(7 to 31.0, *(8..14).map { it to 36.0 }.toTypedArray())
        assertEquals(BreakDue.Stop(i(t(8)), BreakRule.UNION_VTHC),
            due(t(7, 56), unionFromEight, reset, cantStop(t(7, 55)), rule = BreakRule.UNION_VTHC, snooze = t(8, 5)))
        // An unknown 8 am inside the deferral cannot be timed through.
        assertEquals(BreakDue.NoForecast, due(t(7, 56), hotDay.filter { it.time.hour != 8 }, reset, cantStop(t(7, 55)), snooze = t(8, 5)))
        // A deferral over hours below the stop line still lands on its own time.
        assertEquals(BreakDue.At(i(t(8, 5)), HeatLevel.HOT, Cadence(45, 15), snoozed = true), due(t(7, 56), hotDay, reset, cantStop(t(7, 55)), snooze = t(8, 5)))
    }

    // Astra M2 fix round 1, M4: a deferral that lands exactly on the first minute of a stop hour lands on the stop, so the
    // panel says "stop heavy work outdoors from 8:00" before 8 am, not "next break 8:00". A due time the budget itself
    // reaches at 8:00 is not a deferral and keeps its time; the stop then shows from the hour itself (stopNow).
    @Test
    fun aDeferralLandingOnTheFirstMinuteOfAStopHourIsTheStop() {
        val reset = brk(t(7), t(7, 10))   // Hot 45 min from 7:10 → due 7:55
        val extremeFromEight = hours(7 to 33.0, *(8..14).map { it to 39.0 }.toTypedArray())
        assertEquals(BreakDue.Stop(i(t(8)), BreakRule.HEATSHIELD), due(t(7, 52), extremeFromEight, reset, cantStop(t(7, 50)), snooze = t(8)))
        assertEquals(BreakDue.Stop(i(t(8)), BreakRule.HEATSHIELD),
            due(t(7, 20), extremeFromEight, reset, pending = listOf(ScheduledBreak(ScheduledKind.SMOKO, i(t(8))))))
        // A snooze to exactly an unknown hour cannot be timed through either.
        assertEquals(BreakDue.NoForecast, due(t(7, 52), hotDay.filter { it.time.hour != 8 }, reset, cantStop(t(7, 50)), snooze = t(8)))
        // Hot hours: the snooze keeps its own time.
        assertEquals(BreakDue.At(i(t(8)), HeatLevel.HOT, Cadence(45, 15), snoozed = true), due(t(7, 52), hotDay, reset, cantStop(t(7, 50)), snooze = t(8)))
        // Not deferred: the budget fills at 7:59, so 8:00 is the break's own time, even with 8 am at the stop line or unknown.
        val laterReset = brk(t(7, 5), t(7, 15))
        assertEquals(BreakDue.At(i(t(8)), HeatLevel.HOT, Cadence(45, 15)), due(t(7, 20), extremeFromEight, laterReset))
        assertEquals(BreakDue.At(i(t(8)), HeatLevel.HOT, Cadence(45, 15)), due(t(7, 20), hotDay.filter { it.time.hour != 8 }, laterReset))
    }

    // Astra M2 fix round 1, H1: "Can't stop yet" names a break time from the evaluation, only while the snoozed break, and
    // no stop, comes next; otherwise the stop the shift is at or walking to is what the break sheet confirms.
    @Test
    fun breakDueAgainIsTheSnoozedTimeOnlyWhileNoStopComesFirst() {
        val reset = brk(t(7), t(7, 10))   // Hot 45 min from 7:10 → due 7:55
        val answered = OpenShift(row, listOf(reset, cantStop(t(7, 55))))
        val extremeFromEight = hours(7 to 33.0, *(8..14).map { it to 39.0 }.toTypedArray())
        fun eval(open: OpenShift, hours: List<HourHeat>, now: LocalDateTime, rule: BreakRule = BreakRule.HEATSHIELD) =
            ShiftRules.evaluate(open, hours, rule, 570, 720, i(now))
        val hot = eval(answered, hotDay, t(7, 56))
        assertEquals(i(t(8, 5)), hot.breakDueAgainAt); assertNull(hot.stopFrom)
        // The snooze runs into the 8 am stop: no break time, the stop from 8:00.
        val intoStop = eval(answered, extremeFromEight, t(7, 56))
        assertEquals(i(t(8, 5)), intoStop.snoozedUntil)
        assertNull(intoStop.breakDueAgainAt); assertEquals(i(t(8)), intoStop.stopFrom)
        // This hour at the stop line, whatever the walk said: the stop now.
        val extremeNow = hot.copy(current = extremeFromEight.last())
        assertNull(extremeNow.breakDueAgainAt); assertEquals(extremeNow.now, extremeNow.stopFrom)
        assertEquals(i(t(8, 30)), eval(answered, extremeFromEight, t(8, 30)).stopFrom)
        // Union table: 31° then 36° from 8 am, the same.
        val unionFromEight = hours(7 to 31.0, *(8..14).map { it to 36.0 }.toTypedArray())
        val union = eval(answered, unionFromEight, t(7, 56), BreakRule.UNION_VTHC)
        assertNull(union.breakDueAgainAt); assertEquals(i(t(8)), union.stopFrom)
        // No snooze behind the due time, or nothing timed: no break time to name.
        assertNull(eval(OpenShift(row, listOf(reset)), hotDay, t(7, 56)).breakDueAgainAt)
        assertNull(eval(OpenShift(row, listOf(reset, cantStop(t(7, 20)))), hotDay, t(7, 56)).breakDueAgainAt)
        assertNull(eval(answered, emptyList(), t(7, 56)).breakDueAgainAt)
    }

    // Astra M2 #1: the stop line is judged on the current hour alone, under the break table in use; M3's alerts delegate here.
    @Test
    fun stopNowJudgesTheCurrentHourAloneUnderEachBreakTable() {
        fun hour(air: Double, heavy: Boolean = false): HourHeat {
            val base = HeatRules.baseLevel(air)
            return HourHeat(t(13), air, 10.0, 8.0, air - 3.0, air, base, HeatRules.effectiveLevel(base, PersonalFactors(heavyWork = heavy)), false, 5.0, 5.0)
        }
        assertTrue(ShiftRules.stopNow(hour(39.0), BreakRule.HEATSHIELD)); assertFalse(ShiftRules.stopNow(hour(36.0), BreakRule.HEATSHIELD))
        assertTrue(ShiftRules.stopNow(hour(36.0, heavy = true), BreakRule.HEATSHIELD))     // Very hot + heavy work is Extreme for you
        assertTrue(ShiftRules.stopNow(hour(36.0), BreakRule.UNION_VTHC)); assertFalse(ShiftRules.stopNow(hour(35.4), BreakRule.UNION_VTHC))
        assertFalse(ShiftRules.stopNow(hour(35.0, heavy = true), BreakRule.UNION_VTHC))  // the union table reads the heat number only
        assertFalse(ShiftRules.stopNow(null, BreakRule.HEATSHIELD)); assertFalse(ShiftRules.stopNow(null, BreakRule.UNION_VTHC))
        // The evaluation says so for its current hour even when the walk's answer is an earlier Hot due time.
        val hotThenExtreme = hours(7 to 33.0, 8 to 33.0, *(9..14).map { it to 39.0 }.toTypedArray())
        val open = OpenShift(row.copy(heavyWork = false), emptyList())
        val evaluation = ShiftRules.evaluate(open, hotThenExtreme, BreakRule.HEATSHIELD, 570, 720, i(t(9, 10)))
        assertEquals(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, Cadence(45, 15)), evaluation.due)
        assertTrue(evaluation.isDue); assertTrue(evaluation.stopNow)
        assertFalse(ShiftRules.evaluate(open, hotThenExtreme, BreakRule.HEATSHIELD, 570, 720, i(t(8, 10))).stopNow)
    }

    // Astra M2 #5: smoko and lunch fall on the calendar day inside the shift, so a 22:00–10:00 shift is asked about
    // tomorrow's 9:30 smoko, and a shift that starts after lunch time has none.
    @Test
    fun smokoAndLunchOnANightShiftFallOnTheShiftsOwnDay() {
        val tomorrow = date.plusDays(1)
        val night = ShiftRules.pendingScheduled(570, 720, i(t(22)), i(tomorrow.atTime(10, 0)), emptyList())
        assertEquals(listOf(ScheduledBreak(ScheduledKind.SMOKO, i(tomorrow.atTime(9, 30)))), night)
        assertEquals(ScheduledKind.SMOKO, ShiftRules.proposal(night, i(tomorrow.atTime(9, 35)))?.kind)
        // 20:00 to 13:00: both, tomorrow; a day shift keeps its own day.
        assertEquals(listOf(tomorrow.atTime(9, 30), tomorrow.atTime(12, 0)),
            ShiftRules.pendingScheduled(570, 720, i(t(20)), i(tomorrow.atTime(13, 0)), emptyList()).map { ShiftRules.local(it.start) })
        assertEquals(listOf(t(9, 30), t(12)), ShiftRules.pendingScheduled(570, 720, i(shiftStart), i(shiftEnd), emptyList()).map { ShiftRules.local(it.start) })
        // A smoko answered on the shift is answered whatever day it fell on.
        val answered = brk(tomorrow.atTime(9, 30), tomorrow.atTime(9, 50), type = ShiftEventType.SMOKO, reason = null)
        assertTrue(ShiftRules.pendingScheduled(570, 720, i(t(22)), i(tomorrow.atTime(10, 0)), listOf(answered)).isEmpty())
        // The one calculation proposals, "Yes"/"Not yet" and Fix times share: the first time of day inside [start, end).
        assertEquals(i(tomorrow.atTime(9, 30)), ShiftRules.scheduledStart(570, i(t(22)), i(tomorrow.atTime(10, 0))))
        assertEquals(i(t(9, 30)), ShiftRules.scheduledStart(570, i(t(9, 30)), i(t(15))))
        assertNull(ShiftRules.scheduledStart(720, i(t(22)), i(tomorrow.atTime(10, 0))))   // lunch at noon is outside 22:00–10:00
        assertNull(ShiftRules.scheduledStart(570, i(t(9, 31)), i(t(15))))
    }

    // Astra M2 #3: Melbourne's autumn change repeats 02:00–03:00. A break from 02:55 summer time to the repeated 02:10
    // lasts fifteen real minutes, and a shift from 01:00 to 04:00 that night lasts four real hours.
    @Test
    fun aBreakAcrossTheAutumnChangeCountsItsRealMinutes() {
        val brk = ShiftEvent(shiftId = 1, type = ShiftEventType.BREAK, startedAt = autumn(2, 55), endedAt = autumn(2, 10, repeated = true), reason = BreakReason.SHADE)
        assertEquals(listOf(15), ShiftRules.confirmedBreaks(listOf(brk)).map { it.minutes })
        // It resets the clock: 30 minutes worked from the repeated 02:10 to the repeated 02:40.
        val clock = ShiftRules.workClock(on(autumn(1, 0)), listOf(brk), on(autumn(2, 40, repeated = true)))
        assertEquals(on(autumn(2, 10, repeated = true)), clock.since)
        assertEquals(30, clock.workedMinutes)
        val totals = ShiftRules.summarise(on(autumn(1, 0)), on(autumn(4, 0)), listOf(brk), emptyList())
        assertEquals(15, totals.breakMinutes); assertEquals(240 - 15, totals.outdoorMinutes)
        // Hot all night (one 02:00 forecast hour serves both real 2 am hours): 45 minutes from the repeated 02:10.
        val autumnDay = LocalDate.of(2026, 4, 5)
        val night = (0..6).map { h -> val base = HeatRules.baseLevel(33.0); HourHeat(autumnDay.atTime(h, 0), 33.0, 10.0, 8.0, 30.0, 33.0, base, base, false, 0.0, 0.0) }
        assertEquals(on(autumn(2, 55, repeated = true)), ShiftRules.nextBreakDue(clock, night, BreakRule.HEATSHIELD, emptyList(), null, on(autumn(6, 0))).dueAt)
        // Sixteen hours on the wall clock across the change are seventeen real hours: too long.
        assertEquals(TimesError.TOO_LONG, ShiftRules.validateTimes(autumnDay.minusDays(1).atTime(19, 0), autumnDay.atTime(11, 0), emptyList(), latestOf(autumnDay.atTime(12, 0))).shift)
        // The same break picked in Fix times (02:55 to 02:10) is the next 02:10 after its start: fifteen real minutes, valid.
        val picked = ShiftRules.realSpan(autumnDay.atTime(2, 55), autumnDay.atTime(2, 10))
        assertEquals(on(autumn(2, 55)) to on(autumn(2, 10, repeated = true)), picked.start to picked.end)
        assertTrue(ShiftRules.validateTimes(autumnDay.atTime(1, 0), autumnDay.atTime(4, 0),
            listOf(BreakTimes(1, null, autumnDay.atTime(2, 55), autumnDay.atTime(2, 10))), latestOf(autumnDay.atTime(12, 0))).ok)
        // A start taken "now" in the repeated hour is the hour being lived, not the one before it.
        assertEquals(on(autumn(2, 30, repeated = true)), ShiftRules.instantNotAfter(autumnDay.atTime(2, 30), on(autumn(2, 31, repeated = true))))
        assertEquals(on(autumn(2, 30)), ShiftRules.instantNotAfter(autumnDay.atTime(2, 30), on(autumn(2, 31))))
    }

    // Spring: 02:00–03:00 never happens on 4 Oct 2026, so a 01:00–04:00 shift lasts two real hours and a break from
    // 01:55 to 03:05 ten real minutes; the walk steps from 01:59 straight to 03:00.
    @Test
    fun theSpringChangeAddsNoMinutes() {
        val springDay = LocalDate.of(2026, 10, 4)
        fun at(hour: Int, minute: Int = 0) = springDay.atTime(hour, minute).atZone(heatshield.data.MelbourneZone).toInstant().toEpochMilli()
        val brk = ShiftEvent(shiftId = 1, type = ShiftEventType.BREAK, startedAt = at(1, 55), endedAt = at(3, 5), reason = BreakReason.SHADE)
        val totals = ShiftRules.summarise(on(at(1)), on(at(4)), listOf(brk), emptyList())
        assertEquals(10, totals.breakMinutes); assertEquals(120 - 10, totals.outdoorMinutes)
        val night = listOf(0, 1, 3, 4, 5).map { h -> val base = HeatRules.baseLevel(33.0); HourHeat(springDay.atTime(h, 0), 33.0, 10.0, 8.0, 30.0, 33.0, base, base, false, 0.0, 0.0) }
        val clock = ShiftRules.workClock(on(at(1, 30)), emptyList(), on(at(1, 40)))
        assertEquals(on(at(3, 15)), ShiftRules.nextBreakDue(clock, night, BreakRule.HEATSHIELD, emptyList(), null, on(at(5))).dueAt)
        // Seventeen wall-clock hours across the change are sixteen real ones: allowed.
        assertTrue(ShiftRules.validateTimes(springDay.minusDays(1).atTime(18, 0), springDay.atTime(11, 0), emptyList(), latestOf(springDay.atTime(12, 0))).ok)
    }

    // Astra M2 fix round 1, F2/F3/M1: Fix times picks whole wall-clock minutes. A picked minute that is a stored instant's
    // own keeps that instant (seconds, and the repeated autumn hour's offset), so a row left as it was is the stored row;
    // an edited time is read as before.
    @Test
    fun pickedTimesLeftAsStoredKeepTheirStoredInstants() {
        val day = LocalDate.of(2026, 4, 5)
        val shiftStart = on(autumn(1, 0)); val shiftEnd = on(autumn(2, 50, repeated = true))
        val breakStart = on(autumn(2, 20, repeated = true)); val breakEnd = on(autumn(2, 30, repeated = true))
        // Left as stored: the repeated hour stays the repeated hour (re-read, it was an hour earlier).
        assertEquals(BreakSpan(shiftStart, shiftEnd), ShiftRules.keptSpan(day.atTime(1, 0), day.atTime(2, 50), shiftStart, shiftEnd))
        assertEquals(BreakSpan(breakStart, breakEnd), ShiftRules.keptSpan(day.atTime(2, 20), day.atTime(2, 30), breakStart, breakEnd))
        assertEquals(on(autumn(2, 50)), ShiftRules.realSpan(day.atTime(1, 0), day.atTime(2, 50)).end)
        // Edited: read next to the time kept (an end after the kept start, a start not after the kept end), else as realSpan reads it.
        assertEquals(BreakSpan(breakStart, on(autumn(2, 40, repeated = true))), ShiftRules.keptSpan(day.atTime(2, 20), day.atTime(2, 40), breakStart, breakEnd))
        assertEquals(BreakSpan(on(autumn(2, 25, repeated = true)), breakEnd), ShiftRules.keptSpan(day.atTime(2, 25), day.atTime(2, 30), breakStart, breakEnd))
        assertEquals(ShiftRules.realSpan(day.atTime(2, 25), day.atTime(2, 35)), ShiftRules.keptSpan(day.atTime(2, 25), day.atTime(2, 35), breakStart, breakEnd))
        // A first-hour break 02:10–02:40 and the repeated-hour one do not overlap while both are left as stored.
        val firstHour = BreakTimes(1, null, day.atTime(2, 10), day.atTime(2, 40), on(autumn(2, 10)), on(autumn(2, 40)))
        val repeatedHour = BreakTimes(2, null, day.atTime(2, 20), day.atTime(2, 30), breakStart, breakEnd)
        val latest = latestOf(day.atTime(12, 0))
        assertTrue(ShiftRules.validateTimes(day.atTime(1, 0), day.atTime(2, 50), listOf(firstHour, repeatedHour), latest, shiftStart, shiftEnd).ok)
        assertEquals(mapOf(1 to TimesError.OVERLAPS, 2 to TimesError.OVERLAPS),
            ShiftRules.validateTimes(day.atTime(1, 0), day.atTime(2, 50), listOf(firstHour, repeatedHour.copy(storedStart = null, storedEnd = null)), latest).breaks)
        // A break End shift cut to 40 seconds (15:20:10–15:20:50) shows as 15:20–15:20: valid while left as stored, not once picked.
        val cut = BreakTimes(1, null, t(15, 20), t(15, 20), i(t(15, 20)).plusSeconds(10), i(t(15, 20)).plusSeconds(50))
        assertTrue(ShiftRules.validateTimes(t(7), t(15, 20), listOf(cut), i(t(16)), i(t(7)), i(t(15, 20)).plusSeconds(50)).ok)
        assertEquals(mapOf(1 to TimesError.END_BEFORE_START), ShiftRules.validateTimes(t(7), t(15, 20), listOf(cut.copy(storedStart = null, storedEnd = null)), i(t(16))).breaks)
    }

    // Astra M2 fix round 1, F4: Start shift opened in the repeated autumn hour pre-fills from the hour being lived. On a
    // midnight-to-2-am usual day, at the repeated 02:30 the usual finish has passed, so the usual two hours on is 04:30;
    // in the first 02:30 it is the repeated 02:00, still ahead.
    @Test
    fun startShiftOpenedInTheRepeatedAutumnHourPrefillsFromTheHourBeingLived() {
        val day = LocalDate.of(2026, 4, 5)
        assertEquals(day.atTime(4, 30), ShiftRules.defaultFinish(on(autumn(2, 30, repeated = true)), 0, 120))
        assertEquals(day.atTime(2, 0), ShiftRules.defaultFinish(on(autumn(2, 30)), 0, 120))
    }

    @Test
    fun startShiftFinishPickerKeepsAutumnDefaultsAndNormalNightShift() {
        listOf(false to 30L, true to 120L).forEach { (repeated, expectedMinutes) ->
            val now = on(autumn(2, 30, repeated))
            val prefill = ShiftRules.defaultFinish(now, 0, 120)
            val start = ShiftRules.local(now)
            val startAt = ShiftRules.instantNotAfter(start, now)
            val finish = ShiftRules.finishAfter(startAt, prefill.hour * 60 + prefill.minute)
            val minutes = Duration.between(startAt, ShiftRules.instantAfter(finish, startAt)).toMinutes()
            assertEquals("Repeated 02:30: $repeated", expectedMinutes, minutes)
            assertTrue(minutes in 1..ShiftRules.MAX_SHIFT_MINUTES.toLong())
        }
        val nightStart = t(22)
        val nightFinish = ShiftRules.finishAfter(nightStart, 360)
        assertEquals(date.plusDays(1).atTime(6, 0), nightFinish)
        assertEquals(480L, Duration.between(i(nightStart), ShiftRules.instantAfter(nightFinish, i(nightStart))).toMinutes())
        assertEquals(LocalDate.of(2026, 4, 6).atTime(2, 0), ShiftRules.finishAfter(on(autumn(2, 30, repeated = true)), 120))
    }

    /** A wall-clock time on 5 Apr 2026: its first (summer, +11) occurrence, or the [repeated] (+10) one. */
    private fun autumn(hour: Int, minute: Int, repeated: Boolean = false): Long {
        val zoned = LocalDate.of(2026, 4, 5).atTime(hour, minute).atZone(heatshield.data.MelbourneZone)
        return (if (repeated) zoned.withLaterOffsetAtOverlap() else zoned.withEarlierOffsetAtOverlap()).toInstant().toEpochMilli()
    }
    private fun on(epochMillis: Long) = ShiftRules.instant(epochMillis)
    private fun latestOf(time: LocalDateTime) = i(time)

    @Test
    fun startShiftPrefillIsConfirmableAtEveryMinuteOfTheDay() {
        // Start shift keeps the finish as a time of day and wraps it after the start; its Confirm needs 1..960 minutes.
        listOf(420 to 900, 360 to 840, 300 to 1410, 0 to 1439, 1200 to 1439).forEach { (startMinute, endMinute) ->
            (0 until 24 * 60).forEach { minute ->
                listOf(0L, 59L).forEach { seconds ->
                    val now = date.atStartOfDay().plusMinutes(minute.toLong()).plusSeconds(seconds)
                    val prefill = ShiftRules.defaultFinish(now, startMinute, endMinute)
                    val finish = ShiftRules.finishAfter(now, prefill.hour * 60 + prefill.minute)
                    val minutes = Duration.between(now, finish).toMinutes()
                    assertTrue("$startMinute–$endMinute opened at $now: $minutes min", minutes in 1..ShiftRules.MAX_SHIFT_MINUTES.toLong())
                }
            }
        }
    }
}
