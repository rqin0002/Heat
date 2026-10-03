package heatshield.domain

import heatshield.data.BreakReason
import heatshield.data.MelbourneZone
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.WorkSession
import heatshield.data.isBreak
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Which table drives the work clock (user_preferences.breakRule). Both are HeatShield's decisions: the union option
 * adapts VTHC's rows to the heat number and to a clock that drains work rather than counting rest per hour, so it
 * carries [HeatShield rule] and credits VTHC in its text (spec §5.10).
 */
enum class BreakRule(val stored: String, val source: SourceLabel) {
    HEATSHIELD("heatshield", SourceLabel.HEATSHIELD_RULE),
    UNION_VTHC("vthc", SourceLabel.HEATSHIELD_RULE);

    companion object {
        fun fromStored(value: String): BreakRule = entries.firstOrNull { it.stored == value } ?: HEATSHIELD
    }
}

/** Smoko and lunch: proposed at the user's times, counted only once confirmed (spec §4). */
enum class ScheduledKind(val eventType: String, val minutes: Int) {
    SMOKO(ShiftEventType.SMOKO, 20), LUNCH(ShiftEventType.LUNCH, 30);

    companion object {
        fun ofType(type: String): ScheduledKind? = entries.firstOrNull { it.eventType == type }
    }
}

/** A smoko or lunch still to be answered, at the instant its time of day falls inside the shift ([ShiftRules.scheduledStart]). */
data class ScheduledBreak(val kind: ScheduledKind, val start: Instant) {
    val end: Instant get() = start.plus(Duration.ofMinutes(kind.minutes.toLong()))
}

/** An answer still standing on the open shift (spec §5.6): "Can't stop yet" until a break is taken; "No shade here" for the shift. */
enum class OpenAnswerKind { NO_SHADE, CANT_STOP }

/**
 * A stretch of real time on the shift's timeline: a confirmed break, or a span of picked times ([ShiftRules.realSpan]).
 * Instants, so a daylight-saving change neither adds nor removes a minute; [ShiftRules.local] gives its wall-clock times
 * for display.
 */
data class BreakSpan(val start: Instant, val end: Instant) {
    val minutes: Int get() = Duration.between(start, end).toMinutes().toInt()
    operator fun contains(t: Instant): Boolean = !t.isBefore(start) && t.isBefore(end)
}

/**
 * Work since the clock last reset. [since] is the end of the last confirmed break of RESET_MINUTES or more, else the
 * shift start; it is still ahead while a confirmed smoko or lunch runs. [pauses] are the shorter confirmed breaks after
 * it, whole; [workedMinutes] counts only up to now.
 */
data class WorkClock(val since: Instant, val pauses: List<BreakSpan>, val workedMinutes: Int)

sealed interface BreakDue {
    val dueAt: Instant?

    /** Due at [time] under [cadence]; [scheduled] when a pending smoko/lunch covers it; [snoozed] after "Can't stop yet". */
    data class At(val time: Instant, val level: HeatLevel, val cadence: Cadence, val scheduled: ScheduledKind? = null, val snoozed: Boolean = false) : BreakDue {
        override val dueAt: Instant get() = time
    }

    /**
     * The walk reached a stop ([ShiftRules.stopNow]): an Extreme hour under the HeatShield rule, or a heat number of
     * UNION_STOP_C or more under the union table. No work/rest split, and neither a snooze nor a coming smoko/lunch defers
     * it: a due time they move later is walked to again, and a stop on the way is the stop (spec §5.10).
     */
    data class Stop(val from: Instant, val rule: BreakRule) : BreakDue {
        override val dueAt: Instant get() = from
    }

    /** The walk reached the shift end over known hours without draining the budget. */
    data object NoneToday : BreakDue { override val dueAt: Instant? get() = null }
    /** No hours at all, or the walk reached a minute whose hour is unknown: breaks cannot be timed (Global Constraint 10). */
    data object NoForecast : BreakDue { override val dueAt: Instant? get() = null }
}

data class OpenShift(val shift: WorkSession, val events: List<ShiftEvent>)

/**
 * One reading of the open shift at [now], every time an instant on its real timeline ([ShiftRules.local] for display).
 * [current] is the forecast hour holding [now], and [stopNow] judges it alone under [rule], whatever [due] says.
 */
data class ShiftEvaluation(
    val now: Instant, val shiftStart: Instant, val shiftEnd: Instant,
    val clock: WorkClock, val breaks: List<BreakSpan>, val openBreak: ShiftEvent?, val due: BreakDue,
    val pending: List<ScheduledBreak>, val proposal: ScheduledBreak?, val openAnswer: OpenAnswerKind?,
    val snoozedUntil: Instant?, val current: HourHeat?, val rule: BreakRule
) {
    val isDue: Boolean get() = openBreak == null && due.dueAt?.let { !now.isBefore(it) } == true
    val overdueMinutes: Int get() = if (isDue) Duration.between(requireNotNull(due.dueAt), now).toMinutes().toInt() else 0
    /** This hour is at the stop line ([ShiftRules.stopNow]): the panel shows the stop now, independently of any due time. */
    val stopNow: Boolean get() = ShiftRules.stopNow(current, rule)

    /**
     * When "Can't stop yet" brings the break back: the due time the snooze moved ([BreakDue.At.snoozed]), only while no
     * stop comes first. Null when the walk reaches the stop line on the way ([BreakDue.Stop]), when this hour is at it
     * ([stopNow]), or when nothing is timed, so Today's next step, the break sheet's answer line and its reminder header
     * never show a break time past the stop line.
     */
    val breakDueAgainAt: Instant? get() = (due as? BreakDue.At)?.takeIf { it.snoozed && !stopNow }?.time

    /** The stop line this shift is at or walking to: [now] while this hour is at it ([stopNow]), else the walk's stop still ahead; null when neither. */
    val stopFrom: Instant? get() = if (stopNow) now else (due as? BreakDue.Stop)?.from?.takeIf { it.isAfter(now) }
}

data class ShiftTotals(val outdoorMinutes: Int, val breakMinutes: Int, val peak: HourHeat?, val minutesByLevel: Map<HeatLevel, Int>, val longestStretchMinutes: Int)

/**
 * One break row as Fix times and Add a past shift pick it: wall-clock times, made real by [ShiftRules.keptSpan]. A Fix
 * times row of a stored event carries that event's instants ([storedStart], [storedEnd]), so a time left as it was is the
 * stored instant itself; a new row has none and is read as [ShiftRules.realSpan] reads it.
 */
data class BreakTimes(val key: Int, val kind: ScheduledKind?, val start: LocalDateTime, val end: LocalDateTime,
                      val storedStart: Instant? = null, val storedEnd: Instant? = null) {
    val span: BreakSpan get() = ShiftRules.keptSpan(start, end, storedStart, storedEnd)
}
enum class TimesError { END_BEFORE_START, TOO_LONG, IN_FUTURE, OUTSIDE_SHIFT, OVERLAPS }
data class TimesValidation(val shift: TimesError?, val breaks: Map<Int, TimesError>) {
    val ok: Boolean get() = shift == null && breaks.isEmpty()
}

/**
 * The app timer rules (spec §5.10, §10 M2): pure, on the shift's own timeline of real instants, from the epoch millis
 * Room stores. Every duration, order and overlap is measured between instants, so Melbourne's daylight-saving changes
 * neither lose nor add minutes; wall-clock times ([local]) are only for display and for finding an instant's forecast
 * hour. A break of RESET_MINUTES or more resets the clock, a shorter one pauses it, a scheduled smoko/lunch counts only
 * once confirmed, and "break due" is where the hourly cadence drains a work budget. None of this measures recovery; it
 * is an app clock.
 */
object ShiftRules {
    const val RESET_MINUTES = 10
    const val SCHEDULED_GUARD_MINUTES = 15
    const val SNOOZE_MINUTES = 10
    const val AUTO_END_GRACE_MINUTES = 120
    const val PROPOSAL_WINDOW_MINUTES = 90
    const val MAX_SHIFT_MINUTES = 16 * 60

    private val ONE_MINUTE: Duration = Duration.ofMinutes(1)
    private val MAX_SHIFT: Duration = Duration.ofMinutes(MAX_SHIFT_MINUTES.toLong())

    /** Melbourne wall-clock time of [at]: for display and for finding its forecast hour, never for a duration or an order. */
    fun local(at: Instant): LocalDateTime = at.atZone(MelbourneZone).toLocalDateTime()
    fun localTime(epochMillis: Long): LocalDateTime = local(Instant.ofEpochMilli(epochMillis))
    fun instant(epochMillis: Long): Instant = Instant.ofEpochMilli(epochMillis)

    /**
     * [local] as an instant: in the autumn hour that happens twice, its first (summer-time) occurrence; a spring time that
     * never happens moves on by the gap. Picked times that must follow another use [instantAfter] or [realSpan].
     */
    fun instant(local: LocalDateTime): Instant = local.atZone(MelbourneZone).toInstant()
    fun epochMillis(local: LocalDateTime): Long = instant(local).toEpochMilli()

    /** [local] as its latest instant not after [notAfter], else its first: a start taken during the repeated autumn hour is the hour being lived. */
    fun instantNotAfter(local: LocalDateTime, notAfter: Instant): Instant {
        val zoned = local.atZone(MelbourneZone)
        val later = zoned.withLaterOffsetAtOverlap().toInstant()
        return if (!later.isAfter(notAfter)) later else zoned.withEarlierOffsetAtOverlap().toInstant()
    }

    /** [local] as its first instant after [after], else its last: a finish picked in the repeated autumn hour follows its start. */
    fun instantAfter(local: LocalDateTime, after: Instant): Instant {
        val zoned = local.atZone(MelbourneZone)
        val earlier = zoned.withEarlierOffsetAtOverlap().toInstant()
        return if (earlier.isAfter(after)) earlier else zoned.withLaterOffsetAtOverlap().toInstant()
    }

    /**
     * Picked wall-clock times as a real span: [start]'s instant, then [end]'s first instant after it. A break from 02:55
     * to the repeated 02:10 on the autumn night is fifteen real minutes; an end before its start stays before it.
     */
    fun realSpan(start: LocalDateTime, end: LocalDateTime): BreakSpan = keptSpan(start, end, null, null)

    /**
     * Picked wall-clock times as a real span that keeps the stored instants they show. A picked minute that is
     * [storedStart]'s (or [storedEnd]'s) own wall-clock minute ([shows]) is that stored instant, its seconds and its
     * repeated-autumn-hour offset included. An edited time is read next to the one kept: an edited end is the first such
     * time after the start, an edited start before a kept end its latest occurrence not after that end, and with neither
     * kept the span is [realSpan]'s. So a Fix times row left as it was is the stored row, and a note-only save moves
     * nothing: a break cut to under a minute stays valid, and a time stored in the repeated hour stays there (ruling 3:
     * durations come from the stored instants).
     */
    fun keptSpan(start: LocalDateTime, end: LocalDateTime, storedStart: Instant?, storedEnd: Instant?): BreakSpan {
        val keptEnd = storedEnd?.takeIf { shows(it, end) }
        val from = storedStart?.takeIf { shows(it, start) } ?: keptEnd?.let { instantNotAfter(start, it) } ?: instant(start)
        return BreakSpan(from, keptEnd ?: instantAfter(end, from))
    }

    /** True when [picked], a whole minute, is [stored]'s own wall-clock minute (as Fix times shows a stored time). */
    fun shows(stored: Instant, picked: LocalDateTime): Boolean = local(stored).truncatedTo(ChronoUnit.MINUTES) == picked

    /** The shift's planned finish, or [MAX_SHIFT_MINUTES] after its start when none was set. */
    fun plannedEnd(shift: WorkSession): Instant =
        shift.plannedEndAt?.let(::instant) ?: instant(requireNotNull(shift.startedAt) { "Only a shift with a start has a planned end." }).plus(MAX_SHIFT)

    /** Where a running shift's timeline ends at [now]: its planned finish, or [now] once it runs past it. */
    fun shiftEnd(shift: WorkSession, now: Instant): Instant = maxOf(plannedEnd(shift), now)

    /**
     * Places Start shift's picked finish after its real start, keeping a later occurrence of the autumn hour today.
     * [minuteOfDay] is 0–1439; only a finish with no occurrence still ahead rolls into tomorrow, then the screen checks
     * the 16-hour cap. Example: first 02:30 on 5 April 2026 to picked 02:00 is 30 minutes, while 22:00 to 06:00 is tomorrow.
     */
    fun finishAfter(start: Instant, minuteOfDay: Int): LocalDateTime {
        val picked = local(start).toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60)
        return if (instantAfter(picked, start).isAfter(start)) picked else picked.plusDays(1)
    }

    /** Resolves a labelled start using its first autumn occurrence; real screen starts use the [Instant] overload. */
    fun finishAfter(start: LocalDateTime, minuteOfDay: Int): LocalDateTime = finishAfter(instant(start), minuteOfDay)

    /**
     * Start shift's pre-filled finish, so one tap starts the clock at any hour (spec §4). Today's usual finish while it
     * is still ahead; once it has passed, [now] plus the usual day's length, so a shift started after the usual finish
     * runs the usual hours and not until tomorrow's usual finish, a day away. Never more than [MAX_SHIFT_MINUTES] on.
     * Lengths are real time, so the pre-fill is never too long across a daylight-saving change, and [now] is the real
     * instant, so in the repeated autumn hour the pre-fill counts from the hour being lived, not the one before it.
     */
    fun defaultFinish(now: Instant, startMinute: Int, endMinute: Int): LocalDateTime {
        val usualEnd = instantAfter(local(now).toLocalDate().atStartOfDay().plusMinutes(endMinute.toLong()), now)
        // Start shift works in whole minutes, so a usual finish less than a minute away has already passed.
        val finish = if (!usualEnd.isBefore(now.plus(ONE_MINUTE))) usualEnd
            // The usual day is at least an hour (the DAO's rule).
            else now.plus(Duration.ofMinutes((endMinute - startMinute).coerceIn(60, MAX_SHIFT_MINUTES).toLong()))
        return local(minOf(finish, now.plus(MAX_SHIFT)))
    }

    /** [defaultFinish] for a wall-clock [now] that names one instant (the preview's labelled day, and tests). */
    fun defaultFinish(now: LocalDateTime, startMinute: Int, endMinute: Int): LocalDateTime = defaultFinish(instant(now), startMinute, endMinute)

    /** Closed, non-skipped breaks (tapped or confirmed smoko/lunch) starting at or before [until], in time order. */
    fun confirmedBreaks(events: List<ShiftEvent>, until: Instant? = null): List<BreakSpan> = events
        .filter { it.isBreak && !it.skipped && it.endedAt != null }
        .map { BreakSpan(instant(it.startedAt), instant(requireNotNull(it.endedAt))) }
        .filter { it.end.isAfter(it.start) && (until == null || !it.start.isAfter(until)) }
        .sortedBy { it.start }

    /**
     * A confirmed break counts in full from the moment it is confirmed. "Yes" on a smoko or lunch writes its scheduled
     * span, which is usually still running, so each span's whole length decides reset or pause: a running 20-minute
     * smoko puts [WorkClock.since] at its end, and no break falls due while it runs. Pauses keep their whole span for the
     * walk; only the worked minutes stop at [now].
     */
    fun workClock(shiftStart: Instant, events: List<ShiftEvent>, now: Instant): WorkClock {
        val confirmed = confirmedBreaks(events, now)
        val since = confirmed.filter { it.minutes >= RESET_MINUTES }.maxOfOrNull { it.end }?.let { maxOf(it, shiftStart) } ?: shiftStart
        val pauses = confirmed.filter { it.end.isAfter(since) }.map { it.copy(start = maxOf(it.start, since)) }
        val pausedSoFar = pauses.sumOf { Duration.between(it.start, minOf(it.end, now)).toMinutes().coerceAtLeast(0) }.toInt()
        val worked = (Duration.between(since, now).toMinutes().toInt() - pausedSoFar).coerceAtLeast(0)
        return WorkClock(since, pauses, worked)
    }

    /**
     * The forecast hour holding instant [t], found by its wall-clock label (both real 2 am hours of the autumn change read
     * the one 02:00 hour), or null. A missing hour is unknown: it is never read from a neighbouring hour, and never as cool.
     */
    fun hourAt(hours: List<HourHeat>, t: Instant): HourHeat? {
        val at = local(t)
        return hours.firstOrNull { it.time.toLocalDate() == at.toLocalDate() && it.time.hour == at.hour }
    }

    /**
     * The stop line, judged on [current] alone: Extreme for you under the HeatShield rule (a heavy-work bump counts), or
     * a heat number of [HeatRules.UNION_STOP_C] or more under the union table (no bumps). False with no hour. The walk, the
     * panel, Today and the break sheet judge an hour through this, so the stop shows from the hour that reaches it,
     * whatever break was due before (M3's alert stop line delegates here).
     */
    fun stopNow(current: HourHeat?, rule: BreakRule): Boolean = current != null && when (rule) {
        BreakRule.HEATSHIELD -> current.effective == HeatLevel.EXTREME
        BreakRule.UNION_VTHC -> HeatRules.wholeDegrees(current.heat) >= HeatRules.UNION_STOP_C
    }

    /** HeatShield: the effective level's cadence (null at Normal and Extreme). Union table: VTHC's row for the heat number, no personal bumps. */
    fun cadenceFor(hour: HourHeat, rule: BreakRule): Cadence? = when (rule) {
        BreakRule.HEATSHIELD -> HeatRules.suggestedCadence(hour.effective)
        BreakRule.UNION_VTHC -> HeatRules.unionCadence(hour.heat)
    }

    /**
     * Walks forward one real minute at a time from the last clock reset, draining a budget of 1.0 at 1/workMinutes per
     * worked minute of the hour's cadence, so a hotter later hour or heavier work can only bring the break forward. Paused
     * minutes drain nothing. A stop ([stopNow]) returns the stop line instead of a time. A due time that "Can't stop yet"
     * or a pending smoko/lunch moves later is walked on to its new time, minute by minute and that minute included: a
     * stop on the way is the stop from that minute and an unknown hour is NoForecast, so a deferral never carries work
     * past the stop line or through a gap. A minute whose hour is unknown ends the walk with NoForecast: nothing is timed
     * through a gap.
     */
    fun nextBreakDue(clock: WorkClock, hours: List<HourHeat>, rule: BreakRule, pending: List<ScheduledBreak>,
                     snoozedUntil: Instant?, shiftEnd: Instant): BreakDue {
        if (hours.isEmpty()) return BreakDue.NoForecast
        val end = minOf(shiftEnd, clock.since.plus(MAX_SHIFT))
        var spent = 0.0
        var t = clock.since
        while (t.isBefore(end)) {
            if (clock.pauses.none { t in it }) {
                val hour = hourAt(hours, t) ?: return BreakDue.NoForecast
                if (stopNow(hour, rule)) return BreakDue.Stop(t, rule)
                val cadence = cadenceFor(hour, rule)
                if (cadence != null) {
                    spent += 1.0 / cadence.workMinutes
                    if (spent >= 1.0 - 1e-9) {
                        val dueAt = t.plus(ONE_MINUTE)
                        return walkedTo(due(dueAt, hour, cadence, pending, snoozedUntil), dueAt, clock, hours, rule)
                    }
                }
            }
            t = t.plus(ONE_MINUTE)
        }
        return BreakDue.NoneToday
    }

    private fun due(time: Instant, hour: HourHeat, cadence: Cadence, pending: List<ScheduledBreak>, snoozedUntil: Instant?): BreakDue.At {
        val snoozed = snoozedUntil != null && time.isBefore(snoozedUntil)
        val at = if (snoozed) requireNotNull(snoozedUntil) else time
        // No break-due fires within 15 minutes before a pending smoko or lunch: that break covers it.
        pending.firstOrNull { !at.isBefore(it.start.minus(Duration.ofMinutes(SCHEDULED_GUARD_MINUTES.toLong()))) && at.isBefore(it.start) }
            ?.let { return BreakDue.At(it.start, hour.effective, cadence, scheduled = it.kind, snoozed = snoozed) }
        return BreakDue.At(at, hour.effective, cadence, snoozed = snoozed)
    }

    /**
     * A due time moved later is walked to: the minutes from [from], where the budget filled, up to and including the
     * deferred time itself, checked as the walk checks them (paused minutes skipped). The stop from the first minute at
     * the stop line, so a snooze that lands on the first minute of a stop hour is that stop; NoForecast at an unknown
     * hour; else [due]. A due time that was not moved is the budget's own and is returned as it is.
     */
    private fun walkedTo(due: BreakDue.At, from: Instant, clock: WorkClock, hours: List<HourHeat>, rule: BreakRule): BreakDue {
        if (!due.time.isAfter(from)) return due
        var t = from
        while (!t.isAfter(due.time)) {
            if (clock.pauses.none { t in it }) {
                val hour = hourAt(hours, t) ?: return BreakDue.NoForecast
                if (stopNow(hour, rule)) return BreakDue.Stop(t, rule)
            }
            t = t.plus(ONE_MINUTE)
        }
        return due
    }

    /**
     * Where the user's [minuteOfDay] falls inside a shift: its first instant at that wall-clock time from [shiftStart]
     * (inclusive) to [shiftEnd] (exclusive), on whichever calendar day that is, so a 22:00–10:00 shift's 9:30 smoko is
     * tomorrow's. Null when the shift holds no such time. Proposals, "Yes"/"Not yet" and Fix times all place smoko and
     * lunch through this one calculation.
     */
    fun scheduledStart(minuteOfDay: Int, shiftStart: Instant, shiftEnd: Instant): Instant? {
        val lastDay = local(shiftEnd).toLocalDate()
        var day = local(shiftStart).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val zoned = day.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(MelbourneZone)
            listOf(zoned.withEarlierOffsetAtOverlap(), zoned.withLaterOffsetAtOverlap()).map { it.toInstant() }.distinct()
                .firstOrNull { !it.isBefore(shiftStart) && it.isBefore(shiftEnd) }?.let { return it }
            day = day.plusDays(1)
        }
        return null
    }

    /**
     * The user's smoko and lunch inside the shift ([scheduledStart]) that have no smoko/lunch event (taken or skipped)
     * and no other break over them: a tapped break, or the other scheduled break once confirmed, so a smoko confirmed at
     * 11:45 that runs to 12:05 leaves no lunch to propose at 12:00 that would overlap it. In time order.
     */
    fun pendingScheduled(smokoMinute: Int, lunchMinute: Int, shiftStart: Instant, shiftEnd: Instant, events: List<ShiftEvent>): List<ScheduledBreak> {
        val taken = events.filter { it.isBreak && !it.skipped }
            .map { Triple(it.type, instant(it.startedAt), it.endedAt?.let { end -> instant(end) } ?: Instant.MAX) }
        return listOf(ScheduledKind.SMOKO to smokoMinute, ScheduledKind.LUNCH to lunchMinute).mapNotNull { (kind, minute) ->
            val scheduled = scheduledStart(minute, shiftStart, shiftEnd)?.let { ScheduledBreak(kind, it) } ?: return@mapNotNull null
            val answered = events.any { it.type == kind.eventType }
            val covered = taken.any { (type, from, to) -> type != kind.eventType && from.isBefore(scheduled.end) && to.isAfter(scheduled.start) }
            scheduled.takeIf { !answered && !covered }
        }.sortedBy { it.start }
    }

    /**
     * True while a confirmed smoko or lunch covers [atMillis]. "Yes" writes the break's whole scheduled span, which is
     * usually still running, so the gardener is on that break and another one would overlap it.
     */
    fun scheduledBreakRunning(events: List<ShiftEvent>, atMillis: Long): Boolean = events.any {
        ScheduledKind.ofType(it.type) != null && !it.skipped && it.startedAt <= atMillis && (it.endedAt ?: Long.MAX_VALUE) > atMillis
    }

    /** The pending break whose time has come, asked about for PROPOSAL_WINDOW_MINUTES. */
    fun proposal(pending: List<ScheduledBreak>, now: Instant): ScheduledBreak? =
        pending.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.start.plus(Duration.ofMinutes(PROPOSAL_WINDOW_MINUTES.toLong()))) }

    /**
     * SNOOZE_MINUTES after the latest "Can't stop yet". It only ever moves a due time later, and a break that resets the
     * clock puts the next due time past it, so an old reminder needs no expiry.
     */
    fun snoozedUntil(events: List<ShiftEvent>): Instant? =
        events.filter { it.type == ShiftEventType.CANT_STOP }.maxOfOrNull { it.startedAt }?.let { instant(it).plus(Duration.ofMinutes(SNOOZE_MINUTES.toLong())) }

    /**
     * Where saving "Can't stop yet" now would meet the stop line, as [nextBreakDue] walks a snoozed break: this minute
     * while the hour is at the line; the walk's own stop when it already names one (a snooze never moves a stop); else the
     * first stop-line minute from the due break to the snooze's end, ten minutes on. Null when the snooze ends before any
     * stop, or nothing is timed. [hours] are the shift's timing hours. The break sheet's "Can't stop yet" choice and the
     * break-due alert's "In 10 min" both ask this, so neither promises a reminder the stop line would replace.
     */
    fun snoozeStop(evaluation: ShiftEvaluation, hours: List<HourHeat>): Instant? {
        if (evaluation.stopNow) return evaluation.now
        val due = evaluation.due
        if (due is BreakDue.Stop) return due.from
        if (due !is BreakDue.At) return null
        val end = evaluation.now.plus(Duration.ofMinutes(SNOOZE_MINUTES.toLong()))
        if (due.time.isAfter(end)) return null   // the break is due later than the snooze ends: the snooze moves nothing
        return hours.filter { stopNow(it, evaluation.rule) }
            .map { instant(it.time) }
            .filter { start -> start.plus(Duration.ofHours(1)).isAfter(due.time) && !start.isAfter(end) }
            .minOrNull()?.let { maxOf(it, due.time) }
    }

    /** "Can't stop yet" stands until a confirmed break follows it; "No shade here" stands for the shift (it is raised with the team leader). */
    fun openAnswer(events: List<ShiftEvent>): OpenAnswerKind? {
        val lastCantStop = events.filter { it.type == ShiftEventType.CANT_STOP }.maxOfOrNull { it.startedAt }
        val lastBreakEnd = events.filter { it.isBreak && !it.skipped && it.endedAt != null }.maxOfOrNull { requireNotNull(it.endedAt) }
        if (lastCantStop != null && (lastBreakEnd == null || lastBreakEnd < lastCantStop)) return OpenAnswerKind.CANT_STOP
        if (events.any { it.type == ShiftEventType.BREAK && it.reason == BreakReason.NO_SHADE }) return OpenAnswerKind.NO_SHADE
        return null
    }

    fun evaluate(open: OpenShift, hours: List<HourHeat>, rule: BreakRule, smokoMinute: Int, lunchMinute: Int, now: Instant): ShiftEvaluation {
        val start = instant(requireNotNull(open.shift.startedAt) { "Only an open shift is evaluated." })
        val end = shiftEnd(open.shift, now)   // a shift running past its planned finish keeps counting
        val clock = workClock(start, open.events, now)
        val pending = pendingScheduled(smokoMinute, lunchMinute, start, end, open.events)
        val snooze = snoozedUntil(open.events)
        return ShiftEvaluation(
            now = now, shiftStart = start, shiftEnd = end, clock = clock, breaks = confirmedBreaks(open.events),
            openBreak = open.events.firstOrNull { it.isBreak && it.endedAt == null },
            due = nextBreakDue(clock, hours, rule, pending, snooze, end),
            pending = pending, proposal = proposal(pending, now), openAnswer = openAnswer(open.events), snoozedUntil = snooze,
            current = hourAt(hours, now), rule = rule
        )
    }

    /**
     * The forecast hours a stretch from [from] to [to] worked in: each hour's real span (its wall-clock label to the next
     * one, so the autumn 02:00 label spans both real 2 am hours) overlaps it. The hour ending exactly at [from] held none.
     */
    fun hoursOverlapping(hours: List<HourHeat>, from: Instant, to: Instant): List<HourHeat> =
        hours.filter { instant(it.time.plusHours(1)).isAfter(from) && instant(it.time).isBefore(to) }

    /**
     * Every stored minute, from events. Breaks are clipped to the shift and merged; levels come from the hour containing
     * each worked minute. Tracked instants carry seconds, so the shift and every break are first cut to whole minutes:
     * each counted minute then lies on a minute boundary, the per-level minutes add up to at most [ShiftTotals.outdoorMinutes]
     * (exactly, when the hours cover the shift), the longest stretch never exceeds it, and Fix times, which works in whole
     * minutes, changes no total by saving the same times. Minutes are real minutes across a daylight-saving change.
     */
    fun summarise(start: Instant, end: Instant, events: List<ShiftEvent>, hours: List<HourHeat>): ShiftTotals {
        val from = start.truncatedTo(ChronoUnit.MINUTES)
        val to = end.truncatedTo(ChronoUnit.MINUTES)
        val spans = merge(confirmedBreaks(events).map {
            BreakSpan(maxOf(it.start.truncatedTo(ChronoUnit.MINUTES), from), minOf(it.end.truncatedTo(ChronoUnit.MINUTES), to))
        }.filter { it.end.isAfter(it.start) })
        val total = Duration.between(from, to).toMinutes().toInt().coerceAtLeast(0)
        val breakMinutes = spans.sumOf { it.minutes }
        val byLevel = mutableMapOf<HeatLevel, Int>()
        var longest = 0
        var run = 0
        var t = from
        while (t.isBefore(to)) {
            if (spans.any { t in it }) {
                longest = maxOf(longest, run); run = 0
            } else {
                run++
                hourAt(hours, t)?.let { byLevel.merge(it.effective, 1, Int::plus) }
            }
            t = t.plus(ONE_MINUTE)
        }
        longest = maxOf(longest, run)
        val peak = hoursOverlapping(hours, from, to).maxByOrNull { it.heat }
        return ShiftTotals((total - breakMinutes).coerceAtLeast(0), breakMinutes, peak, byLevel, longest)
    }

    /**
     * When a forgotten shift ends by itself: the planned finish, or the last thing the gardener did on the shift if that
     * came later (a break started or ended, an answer, a confirmed smoko or lunch), so work and breaks logged after the
     * planned finish are kept. Posted alerts are app history, not work, and never extend it. The repository ends the
     * shift at this time once [AUTO_END_GRACE_MINUTES] have passed it.
     */
    fun autoEndAt(planned: Instant, events: List<ShiftEvent>): Instant =
        events.filter { it.type != ShiftEventType.ALERT }.flatMap { listOfNotNull(it.startedAt, it.endedAt) }.maxOrNull()?.let { maxOf(planned, instant(it)) } ?: planned

    private fun merge(spans: List<BreakSpan>): List<BreakSpan> = spans.sortedBy { it.start }.fold(mutableListOf()) { acc, span ->
        val last = acc.lastOrNull()
        if (last != null && !span.start.isAfter(last.end)) acc[acc.lastIndex] = last.copy(end = maxOf(last.end, span.end)) else acc += span
        acc
    }

    /**
     * Field-level checks for Fix times and Add a past shift, on the real spans of the picked times ([keptSpan]: a time
     * left as stored is the stored instant, [storedStart]/[storedEnd] for the shift and [BreakTimes.span] for a row).
     * Newly entered times must be no later than [latest], the wall-clock now; unchanged stored instants survive a
     * clock correction. Example: a saved 07:00–09:00 shift stays editable at wall 08:40, but a new 08:50 finish or
     * break time is still in the future, even though it is before the saved finish.
     */
    fun validateTimes(start: LocalDateTime, end: LocalDateTime, breaks: List<BreakTimes>, latest: Instant,
                      storedStart: Instant? = null, storedEnd: Instant? = null): TimesValidation {
        val shift = keptSpan(start, end, storedStart, storedEnd)
        val shiftError = when {
            !shift.end.isAfter(shift.start) -> TimesError.END_BEFORE_START
            Duration.between(shift.start, shift.end).toMinutes() > MAX_SHIFT_MINUTES -> TimesError.TOO_LONG
            (shift.start.isAfter(latest) && shift.start != storedStart) ||
                (shift.end.isAfter(latest) && shift.end != storedEnd) -> TimesError.IN_FUTURE
            else -> null
        }
        val spans = breaks.associate { it.key to it.span }
        val errors = mutableMapOf<Int, TimesError>()
        breaks.forEach { b ->
            val span = spans.getValue(b.key)
            val error = when {
                !span.end.isAfter(span.start) -> TimesError.END_BEFORE_START
                span.start.isBefore(shift.start) || span.end.isAfter(shift.end) -> TimesError.OUTSIDE_SHIFT
                (span.start.isAfter(latest) && span.start != b.storedStart) ||
                    (span.end.isAfter(latest) && span.end != b.storedEnd) -> TimesError.IN_FUTURE
                breaks.any { o -> o.key != b.key && spans.getValue(o.key).let { it.end.isAfter(it.start) && it.start.isBefore(span.end) && it.end.isAfter(span.start) } } -> TimesError.OVERLAPS
                else -> null
            }
            if (error != null) errors[b.key] = error
        }
        return TimesValidation(shiftError, errors)
    }

    /** Monotonic time since the start on the same boot; wall-clock time after a reboot or without an anchor; never negative. */
    fun recoveredElapsedMillis(startedAt: Long, startedElapsed: Long?, bootCount: Int?, wallNow: Long, elapsedNow: Long, bootNow: Int): Long =
        if (startedElapsed != null && bootCount != null && bootCount == bootNow && elapsedNow >= startedElapsed) elapsedNow - startedElapsed
        else (wallNow - startedAt).coerceAtLeast(0)
}
