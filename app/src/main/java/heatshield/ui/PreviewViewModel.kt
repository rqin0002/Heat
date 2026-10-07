// Plays the historical Birrarung Marr day and holds practice shifts until saving succeeds.
// This stays separate from today's weather and real shifts so a demonstration cannot become live data.
package heatshield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import heatshield.data.IndexedReading
import heatshield.data.PreviewShiftResult
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftEvent
import heatshield.data.ShiftEventType
import heatshield.data.isBreak
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatProfile
import heatshield.domain.HourHeat
import heatshield.domain.PreviewHeat
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.WorkTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class PreviewState(val heat: PreviewHeat, val index: Int, val total: Int, val virtualTime: LocalDateTime)

/**
 * The running virtual shift on the labelled day; End freezes a separate result until saving succeeds. [latest] is
 * the latest virtual time the shift has seen. Playback starts the day again at 09:52 after its last reading, and the
 * shift's clock never runs back with it.
 */
data class PreviewShift(val start: LocalDateTime, val heavyWork: Boolean, val events: List<ShiftEvent>, val latest: LocalDateTime = start) {
    /** Now on the shift's own timeline: [virtualTime], never earlier than the latest time the shift has seen. */
    fun nowAt(virtualTime: LocalDateTime): LocalDateTime = maxOf(virtualTime, latest)
}

/**
 * "Yes" or "Not yet" on the preview's smoko or lunch at [minuteOfDay]. One answer per kind, as on a real shift
 * (ShiftRepository.confirmScheduled), so a double tap adds nothing and Fix times never finds two rows over each other.
 */
internal fun PreviewShift.answered(kind: ScheduledKind, taken: Boolean, minuteOfDay: Int): PreviewShift {
    if (events.any { it.type == kind.eventType }) return this
    val start = SensorReplaySimulator.PREVIEW_DATE.atTime(minuteOfDay / 60, minuteOfDay % 60)
    return copy(events = events + ShiftEvent(id = events.size + 1, shiftId = 0, type = kind.eventType, startedAt = ShiftRules.epochMillis(start),
        endedAt = ShiftRules.epochMillis(if (taken) start.plusMinutes(kind.minutes.toLong()) else start), skipped = !taken))
}

/**
 * The preview day's hour-max heat for a virtual shift: every hour of the labelled day, since a preview shift starts at
 * 09:52 and runs past any usual day, through the usual profile with the shift's own heavy-work switch.
 */
internal fun previewShiftHours(rows: List<SensorReading>, index: Int, profile: HeatProfile, heavyWork: Boolean): List<HourHeat> =
    ContextReasoner.previewHeat(rows, index, profile.copy(shiftStartMinute = 0, shiftEndMinute = 24 * 60,
        factors = profile.factors.copy(heavyWork = heavyWork))).dayHours

/** Real milliseconds for [virtualMinutes] at the preview's 45× speed. */
internal fun realDelayMillis(virtualMinutes: Long): Long = virtualMinutes * 60_000L / SensorReplaySimulator.VIRTUAL_SPEEDUP

/** Plays the 9 Jan 2026 sensor day for the Preview screen only. Today never reads this state. */
class PreviewViewModel(application: Application, private val simulator: SensorReplaySimulator) : AndroidViewModel(application) {
    /** The app's constructor, used by `viewModel()`: the bundled file, one reading every 20 s. Tests pass their own simulator. */
    constructor(application: Application) : this(application, SensorReplaySimulator.fromAssets(application))

    private val mutableState = MutableStateFlow<PreviewState?>(null)
    private val mutableUnavailable = MutableStateFlow(false)
    private var profile: HeatProfile? = null
    private var latest: IndexedReading? = null
    private var job: Job? = null
    private var rows: List<SensorReading> = emptyList()
    private val mutableShift = MutableStateFlow<PreviewShift?>(null)
    private val mutablePendingSave = MutableStateFlow<PreviewShiftResult?>(null)

    val state: StateFlow<PreviewState?> = mutableState.asStateFlow()
    val unavailable: StateFlow<Boolean> = mutableUnavailable.asStateFlow()
    val shift: StateFlow<PreviewShift?> = mutableShift.asStateFlow()
    /** The completed shift awaiting a successful save; null before End or after success or explicit discard. */
    val pendingSave: StateFlow<PreviewShiftResult?> = mutablePendingSave.asStateFlow()

    /** Starts at 09:52 the first time; after [stop] it resumes at the last reading shown. */
    fun start(profile: HeatProfile) {
        if (profile == this.profile && job?.isActive == true) return
        this.profile = profile
        play(latest?.index)
    }

    fun next() { latest?.let { play(it.index + 1) } }

    fun stop() { job?.cancel(); job = null }

    /** The preview day's hour-max heat for the virtual shift ([previewShiftHours]). */
    fun dayHours(heavyWork: Boolean): List<HourHeat> {
        val active = profile ?: return emptyList()
        if (rows.isEmpty()) return emptyList()
        return previewShiftHours(rows, latest?.index ?: 0, active, heavyWork)
    }

    /**
     * Starts at the displayed virtual minute, unless a running or unsaved shift already exists.
     * Example: the first 09:52:53 reading starts at 09:52, so the log's times add up to the minutes shown.
     */
    fun startShift(heavyWork: Boolean) {
        val now = virtualNow() ?: return
        if (mutableShift.value == null && mutablePendingSave.value == null) {
            mutableShift.value = PreviewShift(now.truncatedTo(ChronoUnit.MINUTES), heavyWork, emptyList())
            refreshHeat()
        }
    }

    /**
     * Changes the running shift's work setting and redraws its personal heat advice together with its break timing.
     * Example: the bundled 10:52 reading is Very hot with heavy work off and Extreme with it on.
     */
    fun setHeavyWork(on: Boolean) {
        mutableShift.update { it?.copy(heavyWork = on) }
        refreshHeat()
    }

    /** As on a real shift: not while a break runs, a confirmed smoko or lunch included. */
    fun startBreak(reason: String) {
        val virtual = virtualNow() ?: return
        mutableShift.update { shift ->
            if (shift == null) return@update null
            val now = ShiftRules.epochMillis(shift.nowAt(virtual))
            if (shift.events.any { it.isBreak && it.endedAt == null } || ShiftRules.scheduledBreakRunning(shift.events, now)) shift
            else shift.copy(events = shift.events + ShiftEvent(id = shift.events.size + 1, shiftId = 0, type = ShiftEventType.BREAK,
                startedAt = now, reason = reason))
        }
    }

    fun endBreak() {
        val virtual = virtualNow() ?: return
        mutableShift.update { shift ->
            if (shift == null) return@update null
            val now = ShiftRules.epochMillis(shift.nowAt(virtual))
            shift.copy(events = shift.events.map {
                if (it.isBreak && it.endedAt == null) it.copy(endedAt = maxOf(now, it.startedAt)) else it
            })
        }
    }

    fun cantStop() {
        val virtual = virtualNow() ?: return
        mutableShift.update { shift ->
            if (shift == null) return@update null
            val now = ShiftRules.epochMillis(shift.nowAt(virtual))
            shift.copy(events = shift.events + ShiftEvent(id = shift.events.size + 1, shiftId = 0, type = ShiftEventType.CANT_STOP, startedAt = now, endedAt = now))
        }
    }

    fun confirmScheduled(kind: ScheduledKind, taken: Boolean, minuteOfDay: Int) =
        mutableShift.update { shift -> shift?.answered(kind, taken, minuteOfDay) }

    /**
     * Freezes the completed shift for saving; repeated calls return that same result until success or explicit discard.
     * Null means there is no running or unsaved shift. Its end is at least one minute after its start, and no taken
     * break extends beyond that end: a 12:00–12:30 lunch ended at 12:05 becomes 12:00–12:05; a later lunch is removed.
     */
    fun endShift(): PreviewShiftResult? {
        mutablePendingSave.value?.let { return it }
        val shift = mutableShift.value ?: return null
        val end = maxOf(shift.nowAt(virtualNow() ?: shift.latest), shift.start.plusMinutes(1))
        val endMillis = ShiftRules.epochMillis(end)
        val events = shift.events.mapNotNull { event ->
            val span = event.isBreak && !event.skipped
            when {
                !span -> event.takeIf { it.startedAt <= endMillis }
                event.startedAt >= endMillis -> null
                event.endedAt == null || event.endedAt > endMillis -> event.copy(endedAt = endMillis)
                else -> event
            }
        }
        val result = PreviewShiftResult(profile?.taskName ?: WorkTasks.DEFAULT_TASK_NAME, shift.heavyWork, shift.start, end, events, dayHours(shift.heavyWork))
        mutablePendingSave.value = result
        mutableShift.value = null
        refreshHeat()
        return result
    }

    /** Clears practice data after a successful save or an explicit Exit, then restores the usual profile's heat. */
    fun discardShift() {
        mutablePendingSave.value = null
        mutableShift.value = null
        refreshHeat()
    }

    /** Uses the running shift's work setting for every display; off shift, the usual profile remains authoritative. */
    private fun displayProfile(active: HeatProfile): HeatProfile = mutableShift.value?.let { shift ->
        active.copy(factors = active.factors.copy(heavyWork = shift.heavyWork))
    } ?: active

    /** Recalculates the current reading and ribbon immediately when the shift's personal heat setting changes. */
    private fun refreshHeat() {
        val active = profile ?: return
        if (rows.isEmpty()) return
        mutableState.update { state -> state?.copy(heat = ContextReasoner.previewHeat(rows, state.index, displayProfile(active))) }
    }

    private fun virtualNow(): LocalDateTime? = mutableState.value?.virtualTime

    /** The running shift keeps the latest virtual time it has seen, so a loop back to 09:52 never moves its clock back. */
    private fun advanceShift(virtualTime: LocalDateTime) = mutableShift.update { shift ->
        shift?.takeIf { virtualTime.isAfter(it.latest) }?.copy(latest = virtualTime) ?: shift
    }

    /** Plays readings and the virtual clock, using the running shift's current work setting for each new reading. */
    private fun play(from: Int?) {
        val active = profile ?: return
        stop()
        job = viewModelScope.launch {
            try {
                val rows = simulator.rows()
                this@PreviewViewModel.rows = rows
                coroutineScope {
                    launch {   // the 45x clock between readings
                        while (true) {
                            delay(1_000)
                            val shown = latest ?: continue
                            val virtualTime = virtualTimeOf(shown, System.currentTimeMillis())
                            mutableState.update { it?.copy(virtualTime = virtualTime) }
                            advanceShift(virtualTime)
                        }
                    }
                    previewReadings(simulator, from).collect { reading ->
                        latest = reading
                        val virtualTime = virtualTimeOf(reading, System.currentTimeMillis())
                        mutableState.value = PreviewState(ContextReasoner.previewHeat(rows, reading.index, displayProfile(active)), reading.index, rows.size, virtualTime)
                        advanceShift(virtualTime)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableUnavailable.value = true
            }
        }
    }
}

/**
 * Preview stays on the labelled day: after the day's last reading it starts again at
 * [SensorReplaySimulator.PREVIEW_START]. A file without that day fails to the unavailable state; it must never appear under the fixed 9 January banner.
 */
internal fun previewReadings(simulator: SensorReplaySimulator, from: Int?): Flow<IndexedReading> = flow {
    val rows = simulator.rows()
    val start = SensorReplaySimulator.startIndexFor(rows, SensorReplaySimulator.PREVIEW_DATE, SensorReplaySimulator.PREVIEW_START)
    val onDay = { reading: SensorReading ->
        SensorReplaySimulator.localDateTimeOf(reading).toLocalDate() == SensorReplaySimulator.PREVIEW_DATE
    }
    require(rows.isNotEmpty() && onDay(rows[start])) { "The labelled preview day is unavailable." }
    var next = from ?: start
    while (true) {
        emitAll(simulator.readings(next).takeWhile { onDay(it.reading) })
        next = start
    }
}

/** Reading time plus elapsed time at 45x, bounded by one interval and the end of the labelled observation day. */
internal fun virtualTimeOf(played: IndexedReading, nowMillis: Long): LocalDateTime {
    val elapsed = (nowMillis - played.emittedAtMillis).coerceIn(0L, SensorReplaySimulator.INTERVAL_MILLIS)
    val observed = SensorReplaySimulator.localDateTimeOf(played.reading)
    val advanced = observed.plus(Duration.ofMillis(elapsed * SensorReplaySimulator.VIRTUAL_SPEEDUP))
    return minOf(advanced, observed.toLocalDate().atTime(LocalTime.MAX))
}
