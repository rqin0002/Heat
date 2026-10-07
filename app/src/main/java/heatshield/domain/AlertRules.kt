package heatshield.domain

import heatshield.data.AlertReason
import heatshield.data.Freshness
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.isAnswer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Why a break alert stayed quiet. Profile › Alerts and tests read these; the gardener never sees the names. */
enum class QuietReason { ALERTS_OFF, NOTIFICATIONS_BLOCKED, NO_SHIFT, ON_BREAK, NOT_DUE, NORMAL, STALE_FORECAST, ALREADY_ALERTED, CANT_STOP_CAP }

sealed interface BreakAlertDecision {
    /** Post the break-due notification for [due]; [repeat] is the single two-minute repeat of an unanswered alert. */
    data class Post(val due: BreakDue.At, val repeat: Boolean) : BreakAlertDecision
    data class Quiet(val reason: QuietReason) : BreakAlertDecision
}

/**
 * What an alarm wakes the coordinator for. Appended only: AlertScheduler's request codes come from the ordinal. REFRESH
 * only wakes a reconcile when the lock-screen words change (the next hour, a stop ahead, a smoko's end, a break's reset).
 */
enum class AlarmKind { BREAK_DUE, BREAK_REPEAT, STILL_ON_SHIFT, AUTO_END, START_NUDGE, REFRESH }

/** One alarm to arm. [at] is a real instant on the shift's timeline, or the phone's wall clock for a start nudge. */
data class PlannedAlarm(val kind: AlarmKind, val at: Instant, val shiftId: Int = 0, val dueAt: Instant? = null) {
    /** Break alarms ask for the minute; the checks, the automatic end and the nudge are fine arriving late. */
    val exactWanted: Boolean get() = kind == AlarmKind.BREAK_DUE || kind == AlarmKind.BREAK_REPEAT
}

/** Everything the policy reads, already loaded from Room by the caller. [evaluation] is null exactly when [open] is. */
data class AlertInput(
    val alertsOn: Boolean, val notificationsAllowed: Boolean,
    val open: OpenShift?, val evaluation: ShiftEvaluation?,
    val freshness: Freshness, val now: Instant, val usualStartMinute: Int
)

/** [alarms] to arm (none in the past), whether the ongoing timer shows, and what to do about a break due right now. */
data class AlertPlan(val alarms: List<PlannedAlarm>, val ongoing: Boolean, val breakAlert: BreakAlertDecision)

/**
 * Spec §5.12, as pure functions over the M2 evaluation and the shift's events: no buzz at Normal, at most one break alert
 * per level interval of the current clock plus one repeat, "Can't stop yet" re-asks once then stops, and alerts pause while
 * the forecast is stale. A break that falls due inside 15 minutes before an unanswered smoko or lunch waits for it (spec §4)
 * and buzzes at its time if it is still unanswered then: an unconfirmed smoko counts nothing. The ledger is the shift's
 * `alert` rows, so every judgement survives process death and reboot.
 */
object AlertRules {
    const val REPEAT_MINUTES = 2L
    const val MAX_CANT_STOP = 2
    const val NUDGE_LEAD_MINUTES = 20L
    const val OVERRUN_CHECK_MINUTES = 30L
    /** The window asked of setWindow when precise timing is off. A request: Doze may hold the alarm past it, so no copy quotes it as a bound. */
    const val INEXACT_WINDOW_MINUTES = 10L
    /** An inexact nudge that arrives more than an hour after the usual start is too late to be useful. */
    const val NUDGE_LATE_MINUTES = 60L

    /** Alert rows posted since the clock last reset, oldest first. */
    fun alertsPosted(events: List<ShiftEvent>, since: Instant): List<ShiftEvent> = events
        .filter { it.type == ShiftEventType.ALERT && !ShiftRules.instant(it.startedAt).isBefore(since) }
        .sortedBy { it.startedAt }

    /** "Can't stop yet" answers since the clock last reset: the same due break, asked about again. */
    fun cantStopCount(events: List<ShiftEvent>, since: Instant): Int =
        events.count { it.isAnswer && !ShiftRules.instant(it.startedAt).isBefore(since) }

    /**
     * The start of the run of consecutive forecast hours sharing the effective level of the hour that judges [t]
     * (the hour containing it, else the last before it, else the first). The result is a forecast-hour label.
     * A missing hour ends a run. Null with no hours.
     */
    fun levelRunStart(hours: List<HourHeat>, t: Instant): LocalDateTime? {
        val sorted = hours.sortedBy { it.time }
        if (sorted.isEmpty()) return null
        val local = ShiftRules.local(t)
        val index = sorted.indexOfLast { !it.time.isAfter(local) }.coerceAtLeast(0)
        var i = index
        while (i > 0 && sorted[i - 1].effective == sorted[index].effective && sorted[i - 1].time.plusHours(1) == sorted[i].time) i--
        return sorted[i].time
    }

    /**
     * The stop line on the current hour alone (spec §5.10), independent of the break walk: Extreme under the HeatShield
     * rule (the effective level, so a personal bump counts), a heat number of UNION_STOP_C or more under the union table
     * (no bumps). A Hot break that fell due before the hour crossed the line is announced as a stop, not as a break.
     */
    fun stopNow(current: HourHeat?, rule: BreakRule): Boolean = ShiftRules.stopNow(current, rule)

    fun decideBreakAlert(input: AlertInput, hours: List<HourHeat>, repeat: Boolean): BreakAlertDecision {
        if (!input.alertsOn) return BreakAlertDecision.Quiet(QuietReason.ALERTS_OFF)
        if (!input.notificationsAllowed) return BreakAlertDecision.Quiet(QuietReason.NOTIFICATIONS_BLOCKED)
        val open = input.open ?: return BreakAlertDecision.Quiet(QuietReason.NO_SHIFT)
        val evaluation = input.evaluation ?: return BreakAlertDecision.Quiet(QuietReason.NO_SHIFT)
        if (evaluation.openBreak != null) return BreakAlertDecision.Quiet(QuietReason.ON_BREAK)
        // Before "not due": the coordinator times breaks on a current forecast only (shiftTimingHours), so a stale one arrives here as NoForecast.
        if (input.freshness == Freshness.STALE || input.freshness == Freshness.MISSING) return BreakAlertDecision.Quiet(QuietReason.STALE_FORECAST)
        val due = evaluation.due as? BreakDue.At ?: return BreakAlertDecision.Quiet(QuietReason.NOT_DUE)
        // A break waiting for a smoko or lunch (due.scheduled) is due at that minute: quiet until then, and it posts then.
        if (!evaluation.isDue) return BreakAlertDecision.Quiet(QuietReason.NOT_DUE)
        if (due.level == HeatLevel.NORMAL || evaluation.current?.effective == HeatLevel.NORMAL) return BreakAlertDecision.Quiet(QuietReason.NORMAL)
        val since = evaluation.clock.since
        if (cantStopCount(open.events, since) >= MAX_CANT_STOP) return BreakAlertDecision.Quiet(QuietReason.CANT_STOP_CAP)
        val posted = alertsPosted(open.events, since)
        val dueMillis = due.time.toEpochMilli()
        val first = posted.firstOrNull { it.reason == AlertReason.BREAK_DUE && it.dueAt == dueMillis }
        if (first != null) {
            val answered = open.events.any { it.type != ShiftEventType.ALERT && it.startedAt >= first.startedAt }
            val repeated = posted.any { it.reason == AlertReason.REPEAT && it.dueAt == dueMillis }
            return if (repeat && !answered && !repeated) BreakAlertDecision.Post(due, repeat = true)
            else BreakAlertDecision.Quiet(QuietReason.ALREADY_ALERTED)
        }
        if (repeat) return BreakAlertDecision.Quiet(QuietReason.NOT_DUE)
        if (!due.snoozed) {
            val run = levelRunStart(hours, due.time)
            val sameRun = posted.any {
                it.reason == AlertReason.BREAK_DUE && it.level == due.level.name && levelRunStart(hours, ShiftRules.instant(it.startedAt)) == run
            }
            if (sameRun) return BreakAlertDecision.Quiet(QuietReason.ALREADY_ALERTED)
        }
        return BreakAlertDecision.Post(due, repeat = false)
    }

    fun plan(input: AlertInput, hours: List<HourHeat>): AlertPlan {
        var decision = decideBreakAlert(input, hours, repeat = false)
        val deliverable = input.alertsOn && input.notificationsAllowed
        val open = input.open
        if (open == null) {
            // Off shift: resolve the usual start before subtracting the real lead; nudgeDue decides at fire time.
            val nudge = nextNudge(input.now, input.usualStartMinute)
            val alarms = if (deliverable && nudge.isAfter(input.now)) listOf(PlannedAlarm(AlarmKind.START_NUDGE, nudge)) else emptyList()
            return AlertPlan(alarms, ongoing = false, breakAlert = decision)
        }
        // A shift is open: never the start nudge. Without an evaluation (the caller's contract says there is one) nothing is timed.
        val evaluation = input.evaluation
        val alarms = mutableListOf<PlannedAlarm>()
        val plannedEnd = open.shift.plannedEndAt?.let(ShiftRules::instant)
        if (plannedEnd != null) {
            // M2 fix wave (C06): the automatic end is ShiftRules.autoEndAt, the planned finish or a later break or answer.
            val autoEnd = ShiftRules.autoEndAt(plannedEnd, open.events).plus(Duration.ofMinutes(ShiftRules.AUTO_END_GRACE_MINUTES.toLong()))
            if (autoEnd.isAfter(input.now)) alarms += PlannedAlarm(AlarmKind.AUTO_END, autoEnd, open.shift.id)
        }
        if (deliverable) {
            if (plannedEnd != null) {
                val check = plannedEnd.plus(Duration.ofMinutes(OVERRUN_CHECK_MINUTES))
                if (check.isAfter(input.now)) alarms += PlannedAlarm(AlarmKind.STILL_ON_SHIFT, check, open.shift.id)
            }
            val due = evaluation?.due as? BreakDue.At
            if (evaluation != null && due != null && evaluation.openBreak == null && forecastTimesBreaks(input)) {
                val posted = alertsPosted(open.events, evaluation.clock.since)
                val dueMillis = due.time.toEpochMilli()
                val first = posted.firstOrNull { it.reason == AlertReason.BREAK_DUE && it.dueAt == dueMillis }
                if (first == null) {
                    if (due.time.isAfter(input.now)) alarms += PlannedAlarm(AlarmKind.BREAK_DUE, due.time, open.shift.id, due.time)
                } else if (posted.none { it.reason == AlertReason.REPEAT && it.dueAt == dueMillis }) {
                    val repeatAt = ShiftRules.instant(first.startedAt).plus(Duration.ofMinutes(REPEAT_MINUTES))
                    if (repeatAt.isAfter(input.now)) alarms += PlannedAlarm(AlarmKind.BREAK_REPEAT, repeatAt, open.shift.id, due.time)
                    // The repeat's time has passed with no repeat row: its alarm was held (Doze) or dropped (a reboot), and the reconcile
                    // applying this plan cancels whatever was armed, so the repeat is owed now. Answered since, this is still Quiet.
                    else decision = decideBreakAlert(input, hours, repeat = true)
                }
            }
            // Overtime: the evaluation's walk ends at max(planned finish, now), so a break that falls due after the planned
            // finish reads NoneToday until the minute it is due. Walked on to the 16-hour cap it is armed like any other; at
            // its fire the walk ends at that minute and finds the same due time, and an alarm whose shift ended finds none.
            if (evaluation != null && evaluation.openBreak == null && evaluation.due == BreakDue.NoneToday && forecastTimesBreaks(input)) {
                val beyond = ShiftRules.nextBreakDue(evaluation.clock, hours, evaluation.rule, evaluation.pending, evaluation.snoozedUntil,
                    evaluation.shiftStart.plus(Duration.ofMinutes(ShiftRules.MAX_SHIFT_MINUTES.toLong())))
                (beyond as? BreakDue.At)?.takeIf { it.time.isAfter(input.now) }?.let { alarms += PlannedAlarm(AlarmKind.BREAK_DUE, it.time, open.shift.id, it.time) }
            }
            if (evaluation != null) refreshAt(evaluation, input.now)?.let { alarms += PlannedAlarm(AlarmKind.REFRESH, it, open.shift.id) }
        }
        return AlertPlan(alarms, ongoing = deliverable && evaluation != null, breakAlert = decision)
    }

    /**
     * Break alarms are armed only on a current forecast: a stale or missing one times nothing (spec §5.13), and it changes
     * only through a reconcile trigger (a forecast refresh), which re-plans. The "Can't stop yet" cap does not drop the
     * alarm: at the cap the snoozed due is still armed, its fire decides CANT_STOP_CAP (no buzz), and the reconcile that
     * follows the fire moves the lock-screen timer to "Break due" (Global Constraint 3: the break shows as overdue).
     */
    private fun forecastTimesBreaks(input: AlertInput): Boolean =
        input.freshness == Freshness.FRESH || input.freshness == Freshness.AGING

    /**
     * The next time the lock screen's words change with nothing else to wake a reconcile: the next forecast hour (its level
     * and heat, and the stop line judged on it), a stop the walk reaches ahead, the end of a confirmed smoko or lunch still
     * running (the clock resets then), and the minute a running break resets the clock. The earliest after [now].
     */
    private fun refreshAt(evaluation: ShiftEvaluation, now: Instant): Instant? {
        val nextHour = ShiftRules.instant(ShiftRules.local(now).truncatedTo(ChronoUnit.HOURS).plusHours(1))
        val resets = evaluation.openBreak?.let { ShiftRules.instant(it.startedAt).plus(Duration.ofMinutes(ShiftRules.RESET_MINUTES.toLong())) }
        return listOfNotNull(nextHour, (evaluation.due as? BreakDue.Stop)?.from, evaluation.clock.since, resets).filter { it.isAfter(now) }.minOrNull()
    }

    /**
     * Finds the next start reminder strictly after [now], twenty elapsed minutes before a resolved usual start.
     * Resolve each date separately so a clock change cannot move the reminder past work. Example: Melbourne's 03:10
     * start on 4 October 2026 is nudged at 01:50, while a 00:10 start is nudged at 23:50 the previous evening.
     */
    fun nextNudge(now: Instant, usualStartMinute: Int): Instant {
        var day = ShiftRules.local(now).toLocalDate().minusDays(1)
        var nudge = usualStartOn(day, usualStartMinute).minus(Duration.ofMinutes(NUDGE_LEAD_MINUTES))
        while (!nudge.isAfter(now)) {
            day = day.plusDays(1)
            nudge = usualStartOn(day, usualStartMinute).minus(Duration.ofMinutes(NUDGE_LEAD_MINUTES))
        }
        return nudge
    }

    /** Resolves a labelled test/preview time using its first autumn occurrence; alarm callers keep [Instant] values. */
    fun nextNudge(now: LocalDateTime, usualStartMinute: Int): LocalDateTime =
        ShiftRules.local(nextNudge(ShiftRules.instant(now), usualStartMinute))

    /**
     * The fire-time check for the hot-day start nudge, from Room state loaded when the alarm arrives: [now] lies in
     * the real-time window selected by [nudgeDay], with no open or tracked shift and usable Hot-or-higher weather.
     * Example: a 03:10 start on spring-change day accepts 01:50 and 03:00, but expires at 04:10.
     */
    fun nudgeDue(now: Instant, usualStartMinute: Int, dayLevel: HeatLevel, freshness: Freshness, openShift: Boolean, trackedToday: Boolean): Boolean {
        if (openShift || trackedToday) return false
        if (!dayLevel.atLeast(HeatLevel.HOT)) return false
        if (freshness != Freshness.FRESH && freshness != Freshness.AGING) return false
        return nudgeDay(now, usualStartMinute) != null
    }

    /** Resolves a labelled test/preview time using its first autumn occurrence; delivery callers use real instants. */
    fun nudgeDue(now: LocalDateTime, usualStartMinute: Int, dayLevel: HeatLevel, freshness: Freshness, openShift: Boolean, trackedToday: Boolean): Boolean =
        nudgeDue(ShiftRules.instant(now), usualStartMinute, dayLevel, freshness, openShift, trackedToday)

    /**
     * Finds which workday an arriving reminder belongs to, or null outside twenty minutes before to sixty minutes
     * after the start. Both ends use elapsed minutes; the finish is excluded. Example: 23:50 for a 00:10 start belongs
     * to tomorrow, and only the second 02:50 on 5 April 2026 is inside the lead for a 03:10 start.
     */
    fun nudgeDay(now: Instant, usualStartMinute: Int): LocalDate? {
        val today = ShiftRules.local(now).toLocalDate()
        return (-1L..1L).map { today.plusDays(it) }.firstOrNull { day ->
            val start = usualStartOn(day, usualStartMinute)
            !now.isBefore(start.minus(Duration.ofMinutes(NUDGE_LEAD_MINUTES))) && now.isBefore(start.plus(Duration.ofMinutes(NUDGE_LATE_MINUTES)))
        }
    }

    /**
     * Resolves the day's usual start once: first occurrence in autumn, forward by the spring gap. Minutes wrap into
     * one day, so 1450 means 00:10; a saved 02:30 on 5 April 2026 means the first 02:30, with one reminder for that day.
     */
    private fun usualStartOn(day: LocalDate, usualStartMinute: Int): Instant =
        ShiftRules.instant(day.atTime(LocalTime.MIDNIGHT.plusMinutes(Math.floorMod(usualStartMinute, MINUTES_PER_DAY).toLong())))

    private const val MINUTES_PER_DAY = 24 * 60
}
