package heatshield.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import heatshield.domain.BreakTimes
import heatshield.domain.HeatLevel
import heatshield.domain.HourHeat
import heatshield.domain.OpenShift
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.ShiftTotals
import heatshield.domain.TimesError
import heatshield.domain.TimesValidation
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The three readings a shift anchors to. Tests pass a fake; the app passes [AndroidShiftClock]. */
interface ShiftClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
    fun bootCount(): Int
}

class AndroidShiftClock(context: Context) : ShiftClock {
    private val resolver = context.applicationContext.contentResolver
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
}

/** Start shift's choices: wall-clock times as picked; [ShiftRepository.startShift] makes them real instants. */
data class StartShiftInput(val site: String, val task: String, val heavyWork: Boolean, val heavyClothing: Boolean, val startedAt: LocalDateTime, val plannedEnd: LocalDateTime)
/** What Today knew when an event was written: the hour's level and heat, and the due time the event answers. */
data class EventContext(val level: HeatLevel?, val heatC: Double?, val dueAt: Instant?)
data class BreakEdit(val key: Int, val eventId: Int?, val kind: ScheduledKind?, val start: LocalDateTime, val end: LocalDateTime, val taken: Boolean) {
    /** This row as the rules read it, keeping the instants of its stored event [stored] where its times show them ([ShiftRules.keptSpan]). */
    fun times(stored: ShiftEvent?): BreakTimes =
        BreakTimes(key, kind, start, end, stored?.startedAt?.let(ShiftRules::instant), stored?.endedAt?.let(ShiftRules::instant))
}
/** Fix times. Both times null is a legacy row's note-only edit (its typed minutes stay); otherwise both are required. */
data class ShiftTimesEdit(val start: LocalDateTime?, val end: LocalDateTime?, val breaks: List<BreakEdit>, val note: String?) {
    /**
     * The checks Fix times shows and [ShiftRepository.editTimes] enforces, one calculation: the picked times of [shift]
     * and its stored [events], each time left as stored read as that stored instant ([ShiftRules.keptSpan]), so Fix
     * times opens valid on what End shift wrote, even after the wall clock moves backward. Newly picked times must
     * still be no later than [latest]: a stored 09:00 finish at wall 08:40 is allowed, a new 08:50 finish is not.
     * Null while either shift time is blank.
     */
    fun validation(shift: WorkSession, events: List<ShiftEvent>, latest: Instant): TimesValidation? {
        val from = start ?: return null
        val to = end ?: return null
        val byId = events.associateBy { it.id }
        return ShiftRules.validateTimes(from, to, breaks.filter { it.taken }.map { it.times(it.eventId?.let(byId::get)) }, latest,
            shift.startedAt?.let(ShiftRules::instant), shift.endedAt?.let(ShiftRules::instant))
    }
}
data class PastShiftDraft(val site: String, val task: String, val heavyWork: Boolean, val heavyClothing: Boolean, val start: LocalDateTime, val end: LocalDateTime,
                          val breaks: List<BreakTimes>, val note: String?)
data class PreviewShiftResult(val task: String, val heavyWork: Boolean, val start: LocalDateTime, val end: LocalDateTime, val events: List<ShiftEvent>, val hours: List<HourHeat>)

/**
 * The only production writer of work_sessions and shift_events. Every minute stored comes from [ShiftRules.summarise];
 * every instant comes from the shift's own timeline ([now]), as epoch millis, and every duration, order and overlap is
 * measured between instants (picked wall-clock times go through [ShiftRules.realSpan], and in Fix times through
 * [ShiftRules.keptSpan], which keeps a stored instant its picked minute still shows). Failures are [ShiftFailure]s the
 * ViewModel turns into copy.
 */
class ShiftRepository(private val database: HeatShieldDatabase, private val clock: ShiftClock) {
    private val dao = database.dao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOpenShift(ownerId: String): Flow<OpenShift?> = dao.observeOpenShift(ownerId).flatMapLatest { shift ->
        if (shift == null) flowOf(null) else dao.observeEvents(shift.id).map { OpenShift(shift, it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeShift(ownerId: String, id: Int): Flow<OpenShift?> = dao.observeSession(ownerId, id).flatMapLatest { shift ->
        if (shift == null) flowOf(null) else dao.observeEvents(shift.id).map { OpenShift(shift, it) }
    }

    /** The shift's clock: monotonic since it started on this boot, wall time after a reboot. Off shift, the wall clock. */
    fun now(shift: WorkSession?): Instant {
        val wall = clock.wallMillis()
        val started = shift?.startedAt ?: return Instant.ofEpochMilli(wall)
        return Instant.ofEpochMilli(started + ShiftRules.recoveredElapsedMillis(started, shift.startedElapsed, shift.bootCount, wall, clock.elapsedMillis(), clock.bootCount()))
    }

    /**
     * Starts the open shift. A start taken or picked in the repeated autumn hour is the one already lived, and the planned
     * finish is the first such time after it. "Started earlier?" may not reach back over a shift already in the log: the
     * overlap check runs in the insert's own transaction (OVERLAPS), after the one-open-shift rule (SHIFT_OPEN).
     */
    suspend fun startShift(ownerId: String, input: StartShiftInput): Int = database.withTransaction {
        val wall = Instant.ofEpochMilli(clock.wallMillis())
        val startedAt = ShiftRules.instantNotAfter(input.startedAt, wall)
        if (startedAt.isAfter(wall)) throw ShiftFailure(ShiftError.IN_FUTURE)
        val plannedEnd = ShiftRules.instantAfter(input.plannedEnd, startedAt)
        val minutes = Duration.between(startedAt, plannedEnd).toMinutes()
        if (minutes <= 0 || minutes > ShiftRules.MAX_SHIFT_MINUTES) throw ShiftFailure(ShiftError.TOO_LONG)
        if (dao.getOpenShift(ownerId) != null) throw ShiftFailure(ShiftError.SHIFT_OPEN)
        // The new shift is open-ended: any shift in the log that ends after its start overlaps it.
        if (overlapsAnotherShift(ownerId, startedAt.toEpochMilli(), Long.MAX_VALUE, except = null)) throw ShiftFailure(ShiftError.OVERLAPS)
        val row = WorkSession(
            ownerId = ownerId, title = input.task, site = input.site, date = ShiftRules.local(startedAt).toLocalDate().format(RECORD_DATE),
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE,
            startedAt = startedAt.toEpochMilli(), plannedEndAt = plannedEnd.toEpochMilli(), source = ShiftSource.TRACKED,
            heavyWork = input.heavyWork, heavyClothing = input.heavyClothing,
            startedElapsed = elapsedAt(startedAt.toEpochMilli()), bootCount = clock.bootCount()
        )
        dao.startShift(row).toInt()
    }

    /**
     * One tap. A "No shade here" break also flags the shift To raise. A confirmed smoko or lunch that is still running
     * is a break already: another one would overlap it (and Fix times would refuse both), so it is refused as BREAK_OPEN.
     */
    suspend fun startBreak(ownerId: String, reason: String, context: EventContext): Int = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val at = now(shift)
        if (ShiftRules.scheduledBreakRunning(dao.getEvents(shift.id), at.toEpochMilli())) throw ShiftFailure(ShiftError.BREAK_OPEN)
        val id = dao.addEvent(ownerId, event(shift, ShiftEventType.BREAK, at, endedAt = null, reason = reason, context = context)).toInt()
        if (reason == BreakReason.NO_SHADE && shift.status == ShiftStatus.NONE) dao.updateSession(ownerId, shift.copy(status = ShiftStatus.TO_RAISE))
        id
    }

    suspend fun endBreak(ownerId: String) = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val open = dao.getEvents(shift.id).firstOrNull { it.isBreak && it.endedAt == null } ?: throw ShiftFailure(ShiftError.NO_BREAK)
        dao.closeEvent(ownerId, shift.id, open.id, maxOf(now(shift).toEpochMilli(), open.startedAt))
        Unit
    }

    /** "Can't stop yet": a zero-length event that sets the 10-minute reminder and adds no break minutes. */
    suspend fun cantStop(ownerId: String, context: EventContext) = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val at = now(shift)
        dao.addEvent(ownerId, event(shift, ShiftEventType.CANT_STOP, at, endedAt = at, reason = null, context = context))
        Unit
    }

    /** The ledger row for a posted break alert (M3): zero-length, never a break or an answer. [at] is the shift's timeline time it posted. */
    suspend fun recordAlert(ownerId: String, shiftId: Int, reason: String, context: EventContext, at: Instant) = database.withTransaction {
        val shift = dao.getSession(ownerId, shiftId)?.takeIf { it.isOpen } ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        require(reason in AlertReason.all) { "Unknown alert reason." }
        dao.addEvent(ownerId, event(shift, ShiftEventType.ALERT, at, endedAt = at, reason = reason, context = context))
        Unit
    }

    /**
     * "Yes" writes the scheduled break at its scheduled times; "Not yet" writes a skipped row: it counts nothing, is
     * never proposed again, and End shift lists it unticked for Fix times (spec §4). A shift has one smoko and one
     * lunch, so the first answer stands and a second tap writes nothing; Fix times changes an answer. The time is the one
     * the proposal showed ([ShiftRules.scheduledStart] over the shift's span to now), on the shift's own calendar day, so
     * a night shift's 9:30 smoko is tomorrow's; a time the shift does not hold writes nothing. Eligibility is checked
     * against the latest breaks in this transaction: a break open from 09:32 makes the 09:30–09:50 smoko a no-op,
     * so a stale proposal cannot save overlapping breaks.
     */
    suspend fun confirmScheduled(ownerId: String, kind: ScheduledKind, taken: Boolean) = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val events = dao.getEvents(shift.id)
        if (events.any { it.type == kind.eventType }) return@withTransaction
        val preferences = dao.getPreferences(ownerId) ?: UserPreferences(ownerId = ownerId)
        val proposal = ShiftRules.pendingScheduled(preferences.smokoMinute, preferences.lunchMinute,
            ShiftRules.instant(requireNotNull(shift.startedAt)), ShiftRules.shiftEnd(shift, now(shift)), events)
            .firstOrNull { it.kind == kind } ?: return@withTransaction
        dao.addEvent(ownerId, scheduledEvent(shift.id, kind, proposal.start, taken))
        Unit
    }

    suspend fun setHeavyWork(ownerId: String, on: Boolean) = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        if (shift.heavyWork != on) dao.updateSession(ownerId, shift.copy(heavyWork = on))
        Unit
    }

    /**
     * Writes the log. Scheduled breaks that passed with no answer stay proposals: nothing is added here, and the summary
     * lists them unticked for Fix times (spec §4: they count once confirmed). No shift in the log is longer than
     * [ShiftRules.MAX_SHIFT_MINUTES], the limit Start shift and Fix times keep: a later end is cut to it and marked as
     * ended automatically. The end is the shift's real end for every event: a break still open, or running past it (a
     * confirmed smoko writes its whole span), ends there; a break, smoko, lunch or answer after it is dropped; an answer
     * inside the shift stays. So the log holds no time outside its shift, and Fix times opens on valid rows.
     */
    suspend fun endShift(ownerId: String, hours: List<HourHeat>, endedAt: Instant? = null, auto: Boolean = false): Int = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val start = ShiftRules.instant(requireNotNull(shift.startedAt))
        val end = endedAt ?: now(shift)
        if (!end.isAfter(start)) throw ShiftFailure(ShiftError.INVALID_TIMES)
        val capped = minOf(end, start.plus(Duration.ofMinutes(ShiftRules.MAX_SHIFT_MINUTES.toLong())))
        val endMillis = capped.toEpochMilli()
        dao.getEvents(shift.id).forEach { event ->
            // Only a break, smoko or lunch that was taken spans time; anything else (an answer, a "Not yet") is a moment.
            val span = (event.type == ShiftEventType.BREAK || ScheduledKind.ofType(event.type) != null) && !event.skipped
            when {
                !span -> if (event.startedAt > endMillis) dao.dropEvent(shift.id, event.id)
                event.startedAt >= endMillis -> dao.dropEvent(shift.id, event.id)
                event.endedAt == null -> dao.closeEvent(ownerId, shift.id, event.id, endMillis)
                event.endedAt > endMillis -> dao.updateEvent(ownerId, event.copy(endedAt = endMillis))
            }
        }
        val totals = ShiftRules.summarise(start, capped, dao.getEvents(shift.id), hours)
        dao.updateSession(ownerId, withTotals(shift.copy(endedAt = endMillis, autoEnded = auto || capped != end), totals))
        shift.id
    }

    /**
     * A forgotten End shift. The shift ends by itself at [ShiftRules.autoEndAt] (the planned finish, or the last break or
     * answer logged after it) once [ShiftRules.AUTO_END_GRACE_MINUTES] have passed that time, marked for the gardener to
     * fix; returns that end, or null when nothing ended. The check and the end are one transaction, so when two callers
     * find the shift overdue at once (the ticker and the open-shift collector as the app reopens) it ends once and the
     * other call finds nothing to end.
     */
    suspend fun autoEndIfOverdue(ownerId: String, hours: List<HourHeat>): Instant? = database.withTransaction {
        val shift = dao.getOpenShift(ownerId) ?: return@withTransaction null
        val planned = shift.plannedEndAt?.let { ShiftRules.instant(it) } ?: return@withTransaction null
        val now = now(shift)
        val endAt = minOf(ShiftRules.autoEndAt(planned, dao.getEvents(shift.id)), now)
        if (now.isBefore(endAt.plus(Duration.ofMinutes(ShiftRules.AUTO_END_GRACE_MINUTES.toLong())))) return@withTransaction null
        endShift(ownerId, hours, endedAt = endAt, auto = true)
        // The stored end: endShift cuts a shift longer than MAX_SHIFT_MINUTES.
        dao.getSession(ownerId, shift.id)?.endedAt?.let { ShiftRules.instant(it) } ?: endAt
    }

    /**
     * "Still working" on the "Still on shift?" check: the gardener confirmed the shift is still running, so its planned
     * finish moves to now (never earlier than it was, never past [ShiftRules.MAX_SHIFT_MINUTES] from the start). The next
     * check and the automatic end are planned from it, and a shift that is then forgotten ends at this confirmed time,
     * keeping the overtime just confirmed in the log. Nothing moves for a shift that is not the open one.
     */
    suspend fun keepWorking(ownerId: String, shiftId: Int) = database.withTransaction {
        val shift = dao.getOpenShift(ownerId)?.takeIf { it.id == shiftId } ?: throw ShiftFailure(ShiftError.NO_SHIFT)
        val planned = ShiftRules.plannedEnd(shift)
        val cap = ShiftRules.instant(requireNotNull(shift.startedAt)).plus(Duration.ofMinutes(ShiftRules.MAX_SHIFT_MINUTES.toLong()))
        val moved = minOf(maxOf(planned, now(shift)), cap)
        if (moved != planned) dao.updateSession(ownerId, shift.copy(plannedEndAt = moved.toEpochMilli()))
        Unit
    }

    /**
     * Fix times. A row with an [BreakEdit.eventId] updates that event in place, keeping its id, reason, heat context and
     * due time; a scheduled row switched off becomes skipped. A row without an id adds an event (an unticked proposal
     * adds nothing); breaks left out of [edit] are removed; "Can't stop yet" and alert history stay. Heat columns are recomputed
     * only when [hours] has data, otherwise the stored peak and per-level minutes stay (the screen says so). A legacy
     * row with no stored times accepts a note-only edit (both times null) and keeps its typed minutes; both times
     * entered convert it to the measured model. Times that overlap another shift in the log are refused (OVERLAPS).
     * A time left as Fix times showed it keeps its stored instant ([ShiftRules.keptSpan]), so a note-only save changes
     * no time and no total.
     */
    suspend fun editTimes(ownerId: String, shiftId: Int, edit: ShiftTimesEdit, hours: List<HourHeat>) = database.withTransaction {
        val shift = dao.getSession(ownerId, shiftId) ?: throw ShiftFailure(ShiftError.SHIFT_GONE)
        if (shift.isOpen) throw ShiftFailure(ShiftError.SHIFT_OPEN)
        val note = edit.note?.trim()?.takeIf { it.isNotEmpty() }
        val start = edit.start
        val end = edit.end
        if (start == null || end == null) {
            if (start != null || end != null || shift.startedAt != null) throw ShiftFailure(ShiftError.INVALID_TIMES)
            dao.updateSession(ownerId, shift.copy(note = note))
            return@withTransaction
        }
        val existing = dao.getEvents(shiftId).filter { it.isBreak }
        if (edit.validation(shift, existing, Instant.ofEpochMilli(clock.wallMillis()))?.ok != true) throw ShiftFailure(ShiftError.INVALID_TIMES)
        // Times left as stored keep their stored instants (ShiftRules.keptSpan): a note-only save moves nothing, even in
        // the repeated autumn hour, and a break cut to under a minute stays as End shift wrote it.
        val span = ShiftRules.keptSpan(start, end, shift.startedAt?.let(ShiftRules::instant), shift.endedAt?.let(ShiftRules::instant))
        if (overlapsAnotherShift(ownerId, span.start.toEpochMilli(), span.end.toEpochMilli(), except = shiftId)) throw ShiftFailure(ShiftError.OVERLAPS)
        // "Ended automatically" stays only while the finish is the one the app set. Fix times works in whole minutes, so
        // the stored end is compared to the minute; a corrected finish is the gardener's own, not the app's.
        val sameEnd = shift.endedAt?.let { ShiftRules.instant(it).truncatedTo(ChronoUnit.MINUTES) } == span.end.truncatedTo(ChronoUnit.MINUTES)
        dao.updateSession(ownerId, shift.copy(startedAt = span.start.toEpochMilli(), endedAt = span.end.toEpochMilli(),
            date = start.toLocalDate().format(RECORD_DATE), note = note, autoEnded = shift.autoEnded && sameEnd))
        val kept = edit.breaks.mapNotNull { it.eventId }.toSet()
        existing.filter { it.id !in kept }.forEach { dao.deleteEvent(shiftId, it.id) }
        edit.breaks.sortedBy { it.start }.forEach { b ->
            val current = b.eventId?.let { id -> existing.firstOrNull { it.id == id } }
            val times = b.times(current).span
            when {
                current != null -> dao.updateEvent(ownerId, current.copy(startedAt = times.start.toEpochMilli(),
                    endedAt = (if (b.taken) times.end else times.start).toEpochMilli(), skipped = !b.taken && b.kind != null))
                b.kind != null -> if (b.taken) dao.addEvent(ownerId, scheduledEvent(shiftId, b.kind, times.start, taken = true, end = times.end))
                b.taken -> dao.addEvent(ownerId, ShiftEvent(shiftId = shiftId, type = ShiftEventType.BREAK, startedAt = times.start.toEpochMilli(),
                    endedAt = times.end.toEpochMilli(), startedElapsed = elapsedAt(times.start.toEpochMilli()), bootCount = clock.bootCount(), reason = BreakReason.SHADE))
            }
        }
        val saved = requireNotNull(dao.getSession(ownerId, shiftId))
        dao.updateSession(ownerId, withTotals(saved, ShiftRules.summarise(span.start, span.end, dao.getEvents(shiftId), hours), recomputeHeat = hours.isNotEmpty()))
        Unit
    }

    /** Add a past shift: pickers only. No forecast is kept for past days, so the heat columns stay empty. */
    suspend fun addPastShift(ownerId: String, draft: PastShiftDraft): Int = database.withTransaction {
        val validation = ShiftRules.validateTimes(draft.start, draft.end, draft.breaks, Instant.ofEpochMilli(clock.wallMillis()))
        if (!validation.ok) throw ShiftFailure(if (validation.shift == TimesError.IN_FUTURE) ShiftError.IN_FUTURE else ShiftError.INVALID_TIMES)
        val span = ShiftRules.realSpan(draft.start, draft.end)
        if (overlapsAnotherShift(ownerId, span.start.toEpochMilli(), span.end.toEpochMilli(), except = null)) throw ShiftFailure(ShiftError.OVERLAPS)
        val id = dao.saveSession(WorkSession(
            ownerId = ownerId, title = draft.task, site = draft.site, date = draft.start.toLocalDate().format(RECORD_DATE),
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE,
            startedAt = span.start.toEpochMilli(), endedAt = span.end.toEpochMilli(), source = ShiftSource.MANUAL,
            heavyWork = draft.heavyWork, heavyClothing = draft.heavyClothing, note = draft.note?.trim()?.takeIf { it.isNotEmpty() }
        )).toInt()
        draft.breaks.sortedBy { it.start }.forEach { b ->
            val times = ShiftRules.realSpan(b.start, b.end)
            if (b.kind != null) dao.addEvent(ownerId, scheduledEvent(id, b.kind, times.start, taken = true, end = times.end))
            else dao.addEvent(ownerId, ShiftEvent(shiftId = id, type = ShiftEventType.BREAK, startedAt = times.start.toEpochMilli(), endedAt = times.end.toEpochMilli(),
                startedElapsed = elapsedAt(times.start.toEpochMilli()), bootCount = clock.bootCount(), reason = BreakReason.SHADE))
        }
        val saved = requireNotNull(dao.getSession(ownerId, id))
        dao.updateSession(ownerId, withTotals(saved, ShiftRules.summarise(span.start, span.end, dao.getEvents(id), emptyList())))
        id
    }

    /** The labelled preview day's shift, kept out of the way of a real one. */
    suspend fun savePreviewShift(ownerId: String, result: PreviewShiftResult): Int = database.withTransaction {
        if (dao.getOpenShift(ownerId) != null) throw ShiftFailure(ShiftError.SHIFT_OPEN)
        val span = ShiftRules.realSpan(result.start, result.end)
        if (!span.end.isAfter(span.start)) throw ShiftFailure(ShiftError.INVALID_TIMES)
        val id = dao.saveSession(WorkSession(
            ownerId = ownerId, title = result.task, site = WorkSite.defaults.first().name, date = result.start.toLocalDate().format(RECORD_DATE),
            outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE,
            startedAt = span.start.toEpochMilli(), endedAt = span.end.toEpochMilli(), source = ShiftSource.PREVIEW, heavyWork = result.heavyWork
        )).toInt()
        result.events.filter { it.endedAt != null }.forEach { dao.addEvent(ownerId, it.copy(id = 0, shiftId = id)) }
        val saved = requireNotNull(dao.getSession(ownerId, id))
        dao.updateSession(ownerId, withTotals(saved, ShiftRules.summarise(span.start, span.end, dao.getEvents(id), result.hours)))
        id
    }

    suspend fun deleteShift(ownerId: String, id: Int) {
        if (dao.deleteSession(ownerId, id) != 1) throw ShiftFailure(ShiftError.SHIFT_GONE)
    }

    /**
     * True when [startMillis]–[endMillis] overlaps a shift already in the owner's log, the running one included, so the
     * same hours are never logged (and counted in Trends) twice. Preview shifts are a labelled demo day, not work.
     */
    private suspend fun overlapsAnotherShift(ownerId: String, startMillis: Long, endMillis: Long, except: Int?): Boolean =
        dao.getTimedSessions(ownerId).any { other ->
            other.id != except && other.source != ShiftSource.PREVIEW &&
                requireNotNull(other.startedAt) < endMillis && (other.endedAt ?: Long.MAX_VALUE) > startMillis
        }

    private fun elapsedAt(wallMillis: Long): Long = clock.elapsedMillis() - (clock.wallMillis() - wallMillis)

    private fun event(shift: WorkSession, type: String, at: Instant, endedAt: Instant?, reason: String?, context: EventContext) = ShiftEvent(
        shiftId = shift.id, type = type, startedAt = at.toEpochMilli(), endedAt = endedAt?.toEpochMilli(),
        startedElapsed = clock.elapsedMillis(), bootCount = clock.bootCount(), reason = reason,
        level = context.level?.name, heatC = context.heatC, dueAt = context.dueAt?.toEpochMilli()
    )

    private fun scheduledEvent(shiftId: Int, kind: ScheduledKind, start: Instant, taken: Boolean,
                               end: Instant = start.plus(Duration.ofMinutes(kind.minutes.toLong()))) = ShiftEvent(
        shiftId = shiftId, type = kind.eventType, startedAt = start.toEpochMilli(), endedAt = (if (taken) end else start).toEpochMilli(),
        startedElapsed = elapsedAt(start.toEpochMilli()), bootCount = clock.bootCount(), skipped = !taken
    )

    /** Time columns always follow [totals]; the heat columns only when [recomputeHeat] (there were hours to read them from). */
    private fun withTotals(shift: WorkSession, totals: ShiftTotals, recomputeHeat: Boolean = true): WorkSession {
        val outdoor = totals.outdoorMinutes.coerceIn(0, 1440)
        val timed = shift.copy(outdoorMinutes = outdoor, breakMinutes = totals.breakMinutes.coerceIn(0, 1440 - outdoor), longestStretchMinutes = totals.longestStretchMinutes)
        if (!recomputeHeat) return timed
        return timed.copy(
            peakHeat = totals.peak?.heat, peakLevel = totals.peak?.effective?.name,
            normalMinutes = totals.minutesByLevel[HeatLevel.NORMAL] ?: 0, warmMinutes = totals.minutesByLevel[HeatLevel.WARM] ?: 0,
            hotMinutes = totals.minutesByLevel[HeatLevel.HOT] ?: 0, veryHotMinutes = totals.minutesByLevel[HeatLevel.VERY_HOT] ?: 0,
            extremeMinutes = totals.minutesByLevel[HeatLevel.EXTREME] ?: 0
        )
    }

    companion object {
        /** The log's stored date format, unchanged since A2 so search and filters keep working. */
        val RECORD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
    }
}
