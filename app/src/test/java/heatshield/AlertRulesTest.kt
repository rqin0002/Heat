package heatshield

import heatshield.data.AlertReason
import heatshield.data.BreakReason
import heatshield.data.Freshness
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.WorkSession
import heatshield.domain.AlarmKind
import heatshield.domain.AlertInput
import heatshield.domain.AlertRules
import heatshield.domain.BreakAlertDecision.Post
import heatshield.domain.BreakAlertDecision.Quiet
import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatLevel
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.PlannedAlarm
import heatshield.domain.QuietReason
import heatshield.domain.ShiftEvaluation
import heatshield.domain.ShiftRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §5.12 alert policy and the alarm plan, on explicit times. The shift starts 7:00, planned finish 15:00; smoko 9:30, lunch 12:00. */
class AlertRulesTest {
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private fun t(hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    private fun i(at: LocalDateTime): Instant = ShiftRules.instant(at)
    private fun ms(at: LocalDateTime) = ShiftRules.epochMillis(at)

    private fun hour(h: Int, air: Double, heavy: Boolean = false): HourHeat {
        val base = HeatRules.baseLevel(air)
        val effective = HeatRules.effectiveLevel(base, heatshield.domain.PersonalFactors(heavyWork = heavy))
        return HourHeat(t(h), air, 10.0, 8.0, air - 3.0, air, base, effective, HeatRules.wholeDegrees(air) >= 35, 5.0, 5.0)
    }
    /** Hot 6–10, Very hot 11–13, Hot 14–17: three level runs. */
    private val hotDay = (6..17).map { h -> hour(h, if (h in 11..13) 36.0 else 33.0) }
    private val coolDay = (6..17).map { h -> hour(h, 25.0) }

    private val shift = WorkSession(id = 7, ownerId = "w", title = "Push mowing", site = "Birrarung Marr", date = "15 Jan 2026",
        outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ms(t(7)), plannedEndAt = ms(t(15)), source = ShiftSource.TRACKED)

    private fun brk(start: LocalDateTime, end: LocalDateTime?, reason: String = BreakReason.SHADE) =
        ShiftEvent(shiftId = 7, type = ShiftEventType.BREAK, startedAt = ms(start), endedAt = end?.let(::ms), reason = reason)
    private fun cantStop(at: LocalDateTime, due: LocalDateTime) =
        ShiftEvent(shiftId = 7, type = ShiftEventType.CANT_STOP, startedAt = ms(at), endedAt = ms(at), dueAt = ms(due))
    private fun alert(at: LocalDateTime, due: LocalDateTime, level: HeatLevel = HeatLevel.HOT, reason: String = AlertReason.BREAK_DUE) =
        ShiftEvent(shiftId = 7, type = ShiftEventType.ALERT, startedAt = ms(at), endedAt = ms(at), reason = reason, level = level.name, heatC = 33.0, dueAt = ms(due))

    private fun evaluation(now: LocalDateTime, events: List<ShiftEvent>, hours: List<HourHeat> = hotDay, row: WorkSession = shift): ShiftEvaluation =
        ShiftRules.evaluate(OpenShift(row, events), hours, BreakRule.HEATSHIELD, 570, 720, i(now))
    private fun input(now: LocalDateTime, events: List<ShiftEvent> = emptyList(), hours: List<HourHeat> = hotDay, alertsOn: Boolean = true,
                      allowed: Boolean = true, freshness: Freshness = Freshness.FRESH, open: Boolean = true, row: WorkSession = shift): AlertInput {
        val openShift = if (open) OpenShift(row, events) else null
        return AlertInput(alertsOn, allowed, openShift, openShift?.let { evaluation(now, events, hours, row) }, freshness, i(now), usualStartMinute = 420)
    }
    private fun decide(input: AlertInput, hours: List<HourHeat> = hotDay, repeat: Boolean = false) = AlertRules.decideBreakAlert(input, hours, repeat)
    private fun quiet(reason: QuietReason) = Quiet(reason)

    @Test
    fun quietWhenAlertsAreOffBlockedOffShiftOrOnBreak() {
        assertEquals(quiet(QuietReason.ALERTS_OFF), decide(input(t(7, 45), alertsOn = false)))
        assertEquals(quiet(QuietReason.NOTIFICATIONS_BLOCKED), decide(input(t(7, 45), allowed = false)))
        assertEquals(quiet(QuietReason.NO_SHIFT), decide(input(t(7, 45), open = false)))
        assertEquals(quiet(QuietReason.ON_BREAK), decide(input(t(7, 46), listOf(brk(t(7, 45), null)))))
    }

    @Test
    fun quietBeforeDueAtNormalWhenStaleAndUntilTheSmokoItWaitsFor() {
        assertEquals(quiet(QuietReason.NOT_DUE), decide(input(t(7, 30))))
        // No buzz at Normal: a cool day never produces a due time (nextBreakDue → NoneToday).
        assertEquals(quiet(QuietReason.NOT_DUE), decide(input(t(12), hours = coolDay), hours = coolDay))
        assertEquals(quiet(QuietReason.STALE_FORECAST), decide(input(t(7, 45), freshness = Freshness.STALE)))
        assertEquals(quiet(QuietReason.STALE_FORECAST), decide(input(t(7, 45), freshness = Freshness.MISSING)))
        // A 12-minute break ending 8:40 resets the clock; the Hot cadence makes 9:25 due, inside 15 minutes of smoko at 9:30:
        // the break waits for smoko (spec §4). Quiet until 9:30; still unanswered then, it buzzes at 9:30, since an unconfirmed
        // smoko counts nothing, and a plan made before then holds the alarm for 9:30.
        val reset = listOf(brk(t(8, 28), t(8, 40)))
        val waiting = BreakDue.At(i(t(9, 30)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15), scheduled = heatshield.domain.ScheduledKind.SMOKO)
        assertEquals(waiting, input(t(9, 29), reset).evaluation?.due)
        assertEquals(quiet(QuietReason.NOT_DUE), decide(input(t(9, 29), reset)))
        assertEquals(Post(waiting, repeat = false), decide(input(t(9, 30), reset)))
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(9, 30)), 7, i(t(9, 30)))),
            AlertRules.plan(input(t(9, 20), reset), hotDay).alarms.filter { it.kind == AlarmKind.BREAK_DUE })
        // Ten minutes on, no answer and no break: the same alert stays the one alert (the ledger), and its repeat is owed.
        val posted = reset + alert(t(9, 30), t(9, 30))
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(9, 40), posted)))
        assertEquals(Post(waiting, repeat = true), AlertRules.plan(input(t(9, 40), posted), hotDay).breakAlert)
    }

    @Test
    fun aBreakDueAfterThePlannedFinishIsArmedBeforeItAndPostsAtItsTime() {
        // A 7:00 start with a 7:30 planned finish on a Hot day: the walk ends at the planned finish (or now, once past it),
        // so the evaluation says no break today until 7:45 is reached. Overtime still gets its alarm: the plan walks on.
        val short = shift.copy(plannedEndAt = ms(t(7, 30)))
        val early = input(t(7, 10), row = short)
        assertEquals(BreakDue.NoneToday, early.evaluation?.due)
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7, 45)), 7, i(t(7, 45)))),
            AlertRules.plan(early, hotDay).alarms.filter { it.kind == AlarmKind.BREAK_DUE })
        // The same during overtime, before the due minute; at its fire the walk ends at that minute and finds it due.
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7, 45)), 7, i(t(7, 45)))),
            AlertRules.plan(input(t(7, 40), row = short), hotDay).alarms.filter { it.kind == AlarmKind.BREAK_DUE })
        assertEquals(Post(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15)), repeat = false), decide(input(t(7, 45), row = short)))
        // A stale forecast times nothing, overtime included; on a cool day there is nothing to arm.
        assertTrue(AlertRules.plan(input(t(7, 10), row = short, freshness = Freshness.STALE), hotDay).alarms.none { it.kind == AlarmKind.BREAK_DUE })
        assertTrue(AlertRules.plan(input(t(7, 10), row = short, hours = coolDay), coolDay).alarms.none { it.kind == AlarmKind.BREAK_DUE })
    }

    @Test
    fun aRefreshWakesTheTimerWhenItsWordsChange() {
        fun refresh(plan: heatshield.domain.AlertPlan) = plan.alarms.filter { it.kind == AlarmKind.REFRESH }
        // The next forecast hour: its level, heat and stop line.
        assertEquals(listOf(PlannedAlarm(AlarmKind.REFRESH, i(t(8)), 7)), refresh(AlertRules.plan(input(t(7, 10)), hotDay)))
        // A stop ahead: at 10:40, after a reset at 10:30, the walk reaches Extreme at 11:00 before the budget drains.
        val extremeDay = (6..17).map { h -> hour(h, if (h in 11..13) 39.0 else 33.0) }
        val reset = listOf(brk(t(10, 18), t(10, 30)))
        val stopAhead = input(t(10, 40), reset, hours = extremeDay)
        assertEquals(BreakDue.Stop(i(t(11)), BreakRule.HEATSHIELD), stopAhead.evaluation?.due)
        assertEquals(listOf(PlannedAlarm(AlarmKind.REFRESH, i(t(11)), 7)), refresh(AlertRules.plan(stopAhead, extremeDay)))
        // A confirmed smoko still running ends before the hour does: the clock resets at 9:50.
        val smoko = listOf(ShiftEvent(shiftId = 7, type = ShiftEventType.SMOKO, startedAt = ms(t(9, 30)), endedAt = ms(t(9, 50))))
        assertEquals(listOf(PlannedAlarm(AlarmKind.REFRESH, i(t(9, 50)), 7)), refresh(AlertRules.plan(input(t(9, 35), smoko), hotDay)))
        // A break running since 8:00 resets the clock at 8:10.
        assertEquals(listOf(PlannedAlarm(AlarmKind.REFRESH, i(t(8, 10)), 7)), refresh(AlertRules.plan(input(t(8, 3), listOf(brk(t(8), null))), hotDay)))
        // Nothing to show, nothing to wake: alerts off, or off shift.
        assertTrue(refresh(AlertRules.plan(input(t(7, 10), alertsOn = false), hotDay)).isEmpty())
        assertTrue(refresh(AlertRules.plan(input(t(7, 10), open = false), hotDay)).isEmpty())
        assertFalse(PlannedAlarm(AlarmKind.REFRESH, i(t(8))).exactWanted)
    }

    @Test
    fun onePerLevelIntervalThenOneRepeat() {
        // 7:00 start at Hot (45/15): due 7:45.
        assertEquals(Post(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15)), repeat = false), decide(input(t(7, 45))))
        val first = listOf(alert(t(7, 45), t(7, 45)))
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(7, 46), first)))
        assertEquals(Post(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15)), repeat = true), decide(input(t(7, 47), first), repeat = true))
        val repeated = first + alert(t(7, 47), t(7, 45), reason = AlertReason.REPEAT)
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(7, 48), repeated), repeat = true))
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(7, 48), repeated)))
        // Answered (a break started after the first alert): no repeat.
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(7, 47), first + brk(t(7, 46), t(7, 49))), repeat = true))
        // A repeat alarm with no first alert for that due time is nothing to repeat.
        assertEquals(quiet(QuietReason.NOT_DUE), decide(input(t(7, 45)), repeat = true))
        // Same level run, a different due time (a 5-minute pause before the due moved it to 7:50): still one alert for this Hot run.
        val movedDue = listOf(brk(t(7, 10), t(7, 15)), alert(t(7, 45), t(7, 45)))
        assertEquals(i(t(7, 50)), input(t(7, 50), movedDue).evaluation?.due?.dueAt)
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), decide(input(t(7, 50), movedDue)))
        // The run's level changed (the alert was for Very hot; the refreshed hour is Hot): a new alert is allowed.
        val wasVeryHot = listOf(alert(t(7, 30), t(7, 30), level = HeatLevel.VERY_HOT))
        assertTrue(decide(input(t(7, 45), wasVeryHot)) is Post)
        // A clock reset starts a new cycle: nothing posted since 8:12 → the 8:57 due posts.
        val afterReset = first + brk(t(8), t(8, 12))
        assertEquals(i(t(8, 57)), input(t(8, 57), afterReset).evaluation?.due?.dueAt)
        assertTrue(decide(input(t(8, 57), afterReset)) is Post)
    }

    @Test
    fun cantStopReAsksOnceThenStops() {
        val firstAlert = alert(t(7, 45), t(7, 45))
        val oneAnswer = listOf(firstAlert, cantStop(t(7, 46), t(7, 45)))
        val snoozed = input(t(7, 56), oneAnswer)
        assertEquals(BreakDue.At(i(t(7, 56)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15), snoozed = true), snoozed.evaluation?.due)
        assertEquals(Post(snoozed.evaluation?.due as BreakDue.At, repeat = false), decide(snoozed))
        // Before the re-ask, the plan arms it: one answer, the snoozed due ahead.
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7, 56)), 7, i(t(7, 56)))),
            AlertRules.plan(input(t(7, 50), oneAnswer), hotDay).alarms.filter { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        val twoAnswers = oneAnswer + alert(t(7, 56), t(7, 56)) + cantStop(t(7, 57), t(7, 56))
        assertEquals(2, AlertRules.cantStopCount(twoAnswers, i(t(7))))
        assertEquals(quiet(QuietReason.CANT_STOP_CAP), decide(input(t(8, 7), twoAnswers)))
        assertEquals(quiet(QuietReason.CANT_STOP_CAP), decide(input(t(8, 7), twoAnswers), repeat = true))
        // At the cap the snoozed 8:07 due is still armed: this wake refreshes the lock-screen timer to "Break due".
        assertEquals(i(t(8, 7)), input(t(8), twoAnswers).evaluation?.due?.dueAt)
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(8, 7)), 7, i(t(8, 7)))),
            AlertRules.plan(input(t(8), twoAnswers), hotDay).alarms.filter { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        // Its fire buzzes nothing (CANT_STOP_CAP) and re-arms no break alarm; the reconcile after it shows the break as due.
        val capFire = AlertRules.plan(input(t(8, 7), twoAnswers), hotDay)
        assertEquals(quiet(QuietReason.CANT_STOP_CAP), capFire.breakAlert)
        assertTrue(capFire.alarms.none { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        assertTrue(capFire.ongoing)
        // Today still shows the break as overdue: the cap silences the phone, not the screen.
        assertTrue(input(t(8, 7), twoAnswers).evaluation?.isDue == true)
        // A 12-minute break resets the clock and the count; 45 Hot minutes later the next due posts.
        val reset = twoAnswers + brk(t(8, 10), t(8, 22))
        assertEquals(0, AlertRules.cantStopCount(reset, i(t(8, 22))))
        assertEquals(i(t(9, 7)), input(t(9, 7), reset).evaluation?.due?.dueAt)
        assertTrue(decide(input(t(9, 7), reset)) is Post)
    }

    @Test
    fun planArmsBreakDueOverrunAndAutoEndOnShiftAndTheNudgeOffShift() {
        val onShift = AlertRules.plan(input(t(7, 10)), hotDay)
        assertEquals(listOf(
            PlannedAlarm(AlarmKind.AUTO_END, i(t(17)), 7),
            PlannedAlarm(AlarmKind.STILL_ON_SHIFT, i(t(15, 30)), 7),
            PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7, 45)), 7, i(t(7, 45))),
            PlannedAlarm(AlarmKind.REFRESH, i(t(8)), 7)
        ), onShift.alarms)
        assertTrue(onShift.ongoing)
        assertEquals(quiet(QuietReason.NOT_DUE), onShift.breakAlert)
        // Due now and nothing posted: the plan says post, and arms nothing for the break (the repeat follows the post).
        val dueNow = AlertRules.plan(input(t(7, 45)), hotDay)
        assertTrue(dueNow.breakAlert is Post)
        assertTrue(dueNow.alarms.none { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        // First alert posted at 7:45: the repeat is armed for 7:47; after the repeat, no break alarm.
        val first = listOf(alert(t(7, 45), t(7, 45)))
        assertEquals(listOf(PlannedAlarm(AlarmKind.BREAK_REPEAT, i(t(7, 47)), 7, i(t(7, 45)))),
            AlertRules.plan(input(t(7, 46), first), hotDay).alarms.filter { it.kind == AlarmKind.BREAK_REPEAT })
        val repeated = first + alert(t(7, 47), t(7, 45), reason = AlertReason.REPEAT)
        assertTrue(AlertRules.plan(input(t(7, 48), repeated), hotDay).alarms.none { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        // The repeat's time passed with no repeat row (Doze held its alarm, or a reboot dropped it): the plan posts the repeat now
        // and arms nothing for it; once a break answered the first alert, nothing is owed.
        val held = AlertRules.plan(input(t(7, 50), first), hotDay)
        assertEquals(Post(BreakDue.At(i(t(7, 45)), HeatLevel.HOT, heatshield.domain.Cadence(45, 15)), repeat = true), held.breakAlert)
        assertTrue(held.alarms.none { it.kind == AlarmKind.BREAK_DUE || it.kind == AlarmKind.BREAK_REPEAT })
        assertEquals(quiet(QuietReason.ALREADY_ALERTED), AlertRules.plan(input(t(7, 50), first + brk(t(7, 46), t(7, 49))), hotDay).breakAlert)
        // A stale or missing forecast times nothing: even handed hours, the plan arms no break alarm (the timer still shows).
        listOf(Freshness.STALE, Freshness.MISSING).forEach { old ->
            val stale = AlertRules.plan(input(t(7, 10), freshness = old), hotDay)
            assertEquals(listOf(AlarmKind.AUTO_END, AlarmKind.STILL_ON_SHIFT, AlarmKind.REFRESH), stale.alarms.map { it.kind })
            assertEquals(quiet(QuietReason.STALE_FORECAST), stale.breakAlert)
            assertTrue(stale.ongoing)
        }
        // Alerts off, or notifications blocked: auto-end still runs (a Room write), nothing else; no ongoing timer.
        listOf(input(t(7, 10), alertsOn = false), input(t(7, 10), allowed = false)).forEach { quietInput ->
            val plan = AlertRules.plan(quietInput, hotDay)
            assertEquals(listOf(PlannedAlarm(AlarmKind.AUTO_END, i(t(17)), 7)), plan.alarms)
            assertFalse(plan.ongoing)
        }
        // Past the planned finish and its 30-minute check: only what is still ahead. The break after the planned finish moves
        // the automatic end to its end (M2 fix wave, ShiftRules.autoEndAt): 15:55 + 2 h. It also reset the clock, so the
        // evaluation's walk, which ends at now, has no break due yet; the plan walks on and arms the overtime break at 16:40.
        assertEquals(listOf(PlannedAlarm(AlarmKind.AUTO_END, i(t(17, 55)), 7), PlannedAlarm(AlarmKind.BREAK_DUE, i(t(16, 40)), 7, i(t(16, 40))),
            PlannedAlarm(AlarmKind.REFRESH, i(t(17)), 7)), AlertRules.plan(input(t(16), listOf(brk(t(15, 40), t(15, 55)))), hotDay).alarms)
        // Off shift: the nudge for the next usual start, today if ahead, else tomorrow.
        assertEquals(listOf(PlannedAlarm(AlarmKind.START_NUDGE, i(t(6, 40)))), AlertRules.plan(input(t(6), open = false), hotDay).alarms)
        assertEquals(listOf(PlannedAlarm(AlarmKind.START_NUDGE, i(t(6, 40).plusDays(1)))), AlertRules.plan(input(t(6, 50), open = false), hotDay).alarms)
        assertTrue(AlertRules.plan(input(t(6), open = false, alertsOn = false), hotDay).alarms.isEmpty())
        assertFalse(AlertRules.plan(input(t(6), open = false), hotDay).ongoing)
    }

    @Test
    fun nudgeFiresOnlyOnHotDaysWithACurrentForecastAndNoShiftYet() {
        fun due(now: LocalDateTime, level: HeatLevel = HeatLevel.HOT, freshness: Freshness = Freshness.FRESH, open: Boolean = false, tracked: Boolean = false) =
            AlertRules.nudgeDue(now, 420, level, freshness, open, tracked)
        assertTrue(due(t(6, 40))); assertTrue(due(t(7, 59))); assertTrue(due(t(6, 45), HeatLevel.EXTREME, Freshness.AGING))
        assertFalse(due(t(6, 39))); assertFalse(due(t(8)))
        assertFalse(due(t(6, 40), HeatLevel.WARM)); assertFalse(due(t(6, 40), HeatLevel.NORMAL))
        assertFalse(due(t(6, 40), freshness = Freshness.STALE)); assertFalse(due(t(6, 40), freshness = Freshness.MISSING))
        assertFalse(due(t(6, 40), open = true)); assertFalse(due(t(6, 40), tracked = true))
        assertEquals(t(6, 40), AlertRules.nextNudge(t(0, 5), 420))
        assertEquals(t(6, 40).plusDays(1), AlertRules.nextNudge(t(6, 40), 420))
        // Night starts around midnight: a 0:10 start is nudged at 23:50 the evening before, and the next nudge is never behind now.
        assertEquals(t(23, 50), AlertRules.nextNudge(t(6), 10))
        assertEquals(t(23, 50).plusDays(1), AlertRules.nextNudge(t(23, 55), 10))
        assertTrue(AlertRules.nudgeDue(t(23, 50), 10, HeatLevel.HOT, Freshness.FRESH, openShift = false, trackedToday = false))
        // A 23:30 start's nudge arriving late, after midnight, still counts within the hour after the start (0:20); past that hour (0:40) it does not.
        assertTrue(AlertRules.nudgeDue(t(0, 20), 23 * 60 + 30, HeatLevel.HOT, Freshness.FRESH, openShift = false, trackedToday = false))
        assertFalse(AlertRules.nudgeDue(t(0, 40), 23 * 60 + 30, HeatLevel.HOT, Freshness.FRESH, openShift = false, trackedToday = false))
    }

    @Test
    fun startNudgeKeepsTwentyRealMinutesAcrossBothClockChanges() {
        listOf(
            Instant.parse("2026-10-03T14:00:00Z") to Instant.parse("2026-10-03T15:50:00Z"),
            Instant.parse("2026-04-04T13:00:00Z") to Instant.parse("2026-04-04T16:50:00Z"),
            Instant.parse("2026-04-04T16:40:00Z") to Instant.parse("2026-04-04T16:50:00Z")
        ).forEach { (now, expected) ->
            val input = AlertInput(true, true, null, null, Freshness.FRESH, now, 190)
            val nudge = AlertRules.plan(input, emptyList()).alarms.single()
            assertEquals(AlarmKind.START_NUDGE, nudge.kind)
            assertEquals(expected, nudge.at)
            val start = ShiftRules.instant(ShiftRules.local(now).toLocalDate().atTime(3, 10))
            assertEquals(20L, java.time.Duration.between(nudge.at, start).toMinutes())
        }
    }

    @Test
    fun springStartNudgeAcceptsItsRealLeadAndDeliveryAfterTheJump() {
        val spring = LocalDate.of(2026, 10, 4)
        assertTrue(AlertRules.nudgeDue(spring.atTime(1, 50), 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertTrue(AlertRules.nudgeDue(spring.atTime(3, 0), 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertFalse(AlertRules.nudgeDue(spring.atTime(1, 49), 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertFalse(AlertRules.nudgeDue(spring.atTime(4, 10), 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertEquals(spring, AlertRules.nudgeDay(Instant.parse("2026-10-03T15:50:00Z"), 190))
        assertEquals(spring, AlertRules.nudgeDay(Instant.parse("2026-10-03T16:00:00Z"), 190))
    }

    @Test
    fun autumnStartNudgeKeepsTheCorrectOccurrenceAndResolvesAnAmbiguousStartOnce() {
        val firstTwoFifty = Instant.parse("2026-04-04T15:50:00Z")
        val secondTwoFifty = Instant.parse("2026-04-04T16:50:00Z")
        assertFalse(AlertRules.nudgeDue(firstTwoFifty, 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertTrue(AlertRules.nudgeDue(secondTwoFifty, 190, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertNull(AlertRules.nudgeDay(firstTwoFifty, 190))
        assertEquals(LocalDate.of(2026, 4, 5), AlertRules.nudgeDay(secondTwoFifty, 190))

        // A saved 02:30 start names its first occurrence, so it has one 02:10 reminder for the day.
        assertEquals(Instant.parse("2026-04-04T15:10:00Z"), AlertRules.nextNudge(Instant.parse("2026-04-04T14:00:00Z"), 150))
        assertTrue(AlertRules.nudgeDue(Instant.parse("2026-04-04T15:10:00Z"), 150, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertTrue(AlertRules.nudgeDue(Instant.parse("2026-04-04T16:29:00Z"), 150, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertFalse(AlertRules.nudgeDue(Instant.parse("2026-04-04T16:30:00Z"), 150, HeatLevel.HOT, Freshness.FRESH, false, false))
        assertEquals(Instant.parse("2026-04-05T16:10:00Z"), AlertRules.nextNudge(Instant.parse("2026-04-04T16:00:00Z"), 150))
    }

    @Test
    fun levelRunStartFindsTheStartOfTheRunContainingTheHour() {
        assertEquals(t(6), AlertRules.levelRunStart(hotDay, i(t(9, 30))))
        assertEquals(t(11), AlertRules.levelRunStart(hotDay, i(t(12, 10))))
        assertEquals(t(14), AlertRules.levelRunStart(hotDay, i(t(17, 59))))
        assertEquals(t(6), AlertRules.levelRunStart(hotDay, i(t(5))))        // before the first hour: the first hour's run
        assertNull(AlertRules.levelRunStart(emptyList(), i(t(9))))
        // A gap breaks a run even at the same level.
        val gapped = hotDay.filter { it.time.hour != 8 }
        assertEquals(t(9), AlertRules.levelRunStart(gapped, i(t(9, 30))))
        assertTrue(PlannedAlarm(AlarmKind.BREAK_DUE, i(t(7))).exactWanted); assertTrue(PlannedAlarm(AlarmKind.BREAK_REPEAT, i(t(7))).exactWanted)
        assertFalse(PlannedAlarm(AlarmKind.STILL_ON_SHIFT, i(t(7))).exactWanted); assertFalse(PlannedAlarm(AlarmKind.START_NUDGE, i(t(7))).exactWanted)
        // The stop line, judged on this hour alone: Extreme (effective, so heavy work's bump counts) under the HeatShield rule;
        // a heat number of 36° or more (no bumps) under the union table; never without an hour.
        assertTrue(AlertRules.stopNow(hour(13, 39.0), BreakRule.HEATSHIELD)); assertFalse(AlertRules.stopNow(hour(13, 36.0), BreakRule.HEATSHIELD))
        assertTrue(AlertRules.stopNow(hour(13, 36.0, heavy = true), BreakRule.HEATSHIELD))        // Very hot + heavy work = Extreme for you
        assertTrue(AlertRules.stopNow(hour(13, 36.0), BreakRule.UNION_VTHC)); assertFalse(AlertRules.stopNow(hour(13, 35.4), BreakRule.UNION_VTHC))
        assertFalse(AlertRules.stopNow(hour(13, 35.0, heavy = true), BreakRule.UNION_VTHC))      // the union table reads the heat number only: 35° + heavy work (Extreme for you) is below its 36° row
        assertFalse(AlertRules.stopNow(null, BreakRule.HEATSHIELD))
    }
}
