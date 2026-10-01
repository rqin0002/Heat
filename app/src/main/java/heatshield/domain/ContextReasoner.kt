package heatshield.domain

import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftPlan
import heatshield.data.UserPreferences
import heatshield.data.WorkSession
import heatshield.data.WorkContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What Today and the break check-in say to do next, in priority order: an answer the gardener gave that is still
 * open, then the stop line at Extreme, then the day's rhythm, then nothing.
 */
sealed interface NextStep {
    /** An answer still standing on the open shift: "No shade here" (to raise) or "Can't stop yet" (a 10-minute reminder). */
    data class OpenAnswer(val kind: OpenAnswerKind) : NextStep
    /** The day or the current hour is Extreme, or under the union table the heat number reaches 36°: stop heavy work outdoors. */
    data object StopHeavyWork : NextStep
    /** The day's rhythm from the break table in use (`TodayHeat.suggestedCadence`, or the union row for the peak), from a current forecast. */
    data class BreakSuggested(val cadence: Cadence) : NextStep
    data object None : NextStep
}
/**
 * A ranked work window with its stable saved label and display-ready values, without screen wording.
 * Maxima are whole °C, whole humidity percent and a UV category; 31.5 °C / 54.6% / UV 2.5 gives 32 / 55 / MODERATE.
 */
data class WorkWindow(val label: String, val maxAirC: Int, val maxHumidityPct: Int, val uvCategory: UvCategory,
                      val score: Double, val startHour: Int, val endHour: Int)

/**
 * An open shift's real span: its start and its planned finish, as instants. Today and the break sheet read the hours of
 * this span, across midnight, and the hour it is in once it runs past the finish ([ContextReasoner.todayHeat]).
 */
data class ShiftSpan(val start: Instant, val plannedEnd: Instant)

/**
 * Who is working, where and when. Shift minutes count from local midnight; the finish is exclusive. [shift] is the open
 * shift's real span when one is running ([ContextReasoner.effectiveProfile]); its hours then replace the day's.
 */
data class HeatProfile(val site: String, val taskName: String, val shiftStartMinute: Int, val shiftEndMinute: Int,
                       val triggerC: Double, val factors: PersonalFactors, val shift: ShiftSpan? = null)

/**
 * One hour run through [HeatRules] for a profile. [overTrigger] compares the whole degrees of air a screen shows
 * with the crew's rule. [uvForProtection] is clear-sky UV, falling back to all-sky UV.
 */
data class HourHeat(val time: LocalDateTime, val airC: Double, val humidityPct: Double, val windMs: Double,
                    val feelsLikeC: Double, val heat: Double, val base: HeatLevel, val effective: HeatLevel,
                    val overTrigger: Boolean, val uv: Double?, val uvForProtection: Double?)

/** Shift hours whose air, in whole degrees, reaches the crew's hot-day rule. Never merged into the heat level. */
data class CrewRuleLine(val triggerC: Double, val firstHour: LocalTime?, val hoursInShift: Int, val peakAirC: Double?)

/** From the start of the first hour with a whole UV index of 3 or more (raw 2.5 counts) to the end of the last one. */
data class SunProtection(val from: LocalTime, val until: LocalTime)

/**
 * Coverage of one expected time window, independent of age. [missing] lists real wall-clock hours without data, with
 * their dates (a night shift's window crosses midnight). [availableUntil] ends the continuously covered prefix from the
 * window start, capped at its finish; null means the first expected hour is absent. Data after a gap does not extend it;
 * 00:00 means the following day boundary.
 */
data class Coverage(val complete: Boolean, val availableUntil: LocalTime?, val missing: List<LocalDateTime>)

/** Everything Today shows, worked out from the cached forecast alone. */
data class TodayHeat(
    val date: LocalDate, val site: String,
    // Off shift: forecast hours dated `date` whose hour start is in [shiftStart floor-hour, shiftEnd). On shift: the hours
    // of the open shift's real span (HeatProfile.shift), across midnight, and the hour it is in once past its finish.
    val hours: List<HourHeat>,
    val coverage: Coverage,               // which shift hours `hours` covers; "during your shift" only when complete
    val uvCoverage: Coverage,             // independent coverage of all real hours of `date` with UV data
    val now: HourHeat?,                   // hour containing clock time, if in `hours`
    val peak: HourHeat?,                  // max `heat` in `hours` (earliest on ties)
    val dayLevel: HeatLevel,              // peak?.effective ?: NORMAL
    val bumped: Boolean,                  // peak != null && peak.effective != peak.base
    val crewRule: CrewRuleLine,
    val heavyJobsBefore: LocalTime?,      // first hour in `hours` where heavy work (with the profile's other factors) is >= HOT, or overTrigger; null if none
    val uvPeak: UvCategory?,              // category of max uvForProtection over all hours dated `date`; null if none
    val sunProtection: SunProtection?,    // first..(last+1h) hour dated `date` where HeatRules.needsSunProtection(uvForProtection): whole UV index >= 3
    val suggestedCadence: Cadence?,       // HeatRules.suggestedCadence(dayLevel): null on a Normal or an Extreme day
    val freshness: Freshness, val updatedAtMillis: Long?, val refreshedBy: String?, val error: String?
)

/** Pure reasoning over forecast, profile and time: no Android, storage or network calls. */
object ContextReasoner {
    private const val HEAVY_CLOTHING = "Heavy protective clothing"
    private const val WINDOW_HOURS = 2
    private const val LAST_WINDOW_START_HOUR = 21   // a 21:00–23:00 window still ends on the day's last forecast hour

    /** A schema default is not a task choice. Preserve v1 effort until setup confirms a task. */
    fun usualEffort(preferences: UserPreferences): Effort =
        if (preferences.setupDone) WorkTasks.effortFor(preferences.task) else Effort.fromLabel(preferences.effort)

    fun profileOf(preferences: UserPreferences, today: LocalDate): HeatProfile {
        // "New or back from leave" has an exclusive end at the start of a day (Task 10 stores the start of day +7),
        // so it counts while that end is after the start of today: days 0–6 on, day +7 off.
        val startOfToday = today.atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
        return HeatProfile(
            site = preferences.site, taskName = preferences.task,
            shiftStartMinute = preferences.shiftStartMinute, shiftEndMinute = preferences.shiftEndMinute,
            triggerC = preferences.hotDayTrigger,
            factors = PersonalFactors(
                heavyWork = usualEffort(preferences) == Effort.HEAVY,
                heavyClothing = preferences.clothing == HEAVY_CLOTHING,
                acclimatising = preferences.acclimatisingUntil?.let { it > startOfToday } == true
            )
        )
    }

    /**
     * Today's work conditions, one source for Today, Break and Plan. An open shift of this owner comes first: its park,
     * work, heavy-work and heavy-clothing switches and its own real span ([HeatProfile.shift]), so Today reads the shift's
     * own hours. Otherwise a plan saved for today, otherwise the usual day. The crew rule and acclimatisation are always
     * the worker's own.
     */
    fun effectiveProfile(preferences: UserPreferences, plan: ShiftPlan?, today: LocalDate, shift: WorkSession? = null): HeatProfile {
        val usual = profileOf(preferences, today)
        if (shift != null && shift.isOpen && shift.ownerId == preferences.ownerId) {
            val startAt = ShiftRules.instant(requireNotNull(shift.startedAt))
            val start = ShiftRules.local(startAt)
            val end = shift.plannedEndAt?.let(ShiftRules::localTime) ?: start.plusHours(8)
            return usual.copy(
                site = shift.site, taskName = shift.title,
                shiftStartMinute = start.hour * 60 + start.minute,
                shiftEndMinute = if (end.toLocalDate() == start.toLocalDate()) end.hour * 60 + end.minute else 1439,
                factors = usual.factors.copy(heavyWork = shift.heavyWork, heavyClothing = shift.heavyClothing),
                shift = ShiftSpan(startAt, ShiftRules.plannedEnd(shift))
            )
        }
        if (plan == null || plan.ownerId != preferences.ownerId || plan.context.date != today) return usual
        return usual.copy(
            site = plan.site, taskName = plan.work,
            shiftStartMinute = plan.startHour * 60 + plan.startMinute, shiftEndMinute = plan.endMinute,
            factors = usual.factors.copy(heavyWork = Effort.fromLabel(plan.effort) == Effort.HEAVY, heavyClothing = plan.clothing == HEAVY_CLOTHING)
        )
    }

    fun hourHeat(time: LocalDateTime, airC: Double, humidityPct: Double, windMs: Double,
                 uv: Double?, uvClearSky: Double?, profile: HeatProfile): HourHeat {
        val heat = HeatRules.heatNumber(airC, humidityPct, windMs)
        val base = HeatRules.baseLevel(heat)
        return HourHeat(time, airC, humidityPct, windMs, HeatRules.apparentTemperature(airC, humidityPct, windMs),
            heat, base, HeatRules.effectiveLevel(base, profile.factors),
            // Crew rules are written in air temperature, so the trigger never uses the heat number. It compares the
            // whole degrees a screen shows, so 34.5° (shown 35°) reaches a 35° rule and 34.4° (shown 34°) does not.
            overTrigger = HeatRules.wholeDegrees(airC) >= profile.triggerC, uv = uv, uvForProtection = uvClearSky ?: uv)
    }

    /**
     * Describes the selected workday while measuring forecast age at the real [clock] instant.
     * A null [date] selects today in Melbourne. Choosing another date never advances the freshness clock:
     * for tomorrow's 00:10 start at 23:50, an 18:00 fetch is 5 h 50 min old and still usable.
     */
    fun todayHeat(forecast: ForecastSnapshot?, profile: HeatProfile, clock: Clock, date: LocalDate? = null): TodayHeat {
        val nowMillis = clock.millis()
        val nowAt = Instant.ofEpochMilli(nowMillis)
        val nowLocal = nowAt.atZone(MelbourneZone).toLocalDateTime()
        val today = date ?: nowLocal.toLocalDate()
        val matching = forecast?.takeIf { it.site == profile.site }
        val freshness = matching?.freshness(nowMillis, today) ?: Freshness.MISSING
        val usable = matching?.takeIf { freshness != Freshness.MISSING }
        // Labels were converted to Melbourne wall-clock time at merge (WeatherRepository.merge), so a DST-start day has
        // 23 hours with no 02:00, and nothing is converted here.
        val validHours = usable?.hours.orEmpty()
            .filter { it.isValid }
            .mapNotNull { hour ->
                hour.localTime?.let { time -> hourHeat(time, hour.temperature, hour.humidity, hour.windSpeed, hour.uv, hour.uvClearSky, profile) }
            }
            .distinctBy { it.time }
            .sortedBy { it.time }
        // Only valid hours dated today count for the day (its UV, and the usual day's shift), so a cache that ends
        // yesterday or starts tomorrow never supplies today's peak.
        val dayHours = validHours.filter { it.time.toLocalDate() == today }
        // The shift's wall-clock hours, and where its coverage ends: an open shift's own span, across midnight and on into
        // overtime; otherwise the usual or planned day's hours that exist today, so a DST-start day's skipped 02:00 is
        // never missing.
        val (expected, coverageEnd) = profile.shift?.let { spanHours(it, nowAt) } ?: dayShiftHours(today, profile)
        val expectedSet = expected.toSet()
        val hours = validHours.filter { it.time in expectedSet }
        // Coverage is separate from freshness: a fresh forecast can stop at noon, an old one can cover the shift.
        val expectedDayHours = (0..23).map { today.atTime(it, 0) }
            .filter { it.atZone(MelbourneZone).toLocalDateTime() == it }
        val covered = hours.map { it.time }.toSet()
        val uvCovered = dayHours.filter { it.uvForProtection != null }.map { it.time }.toSet()
        val currentHour = nowLocal.truncatedTo(ChronoUnit.HOURS)
        val peak = hours.maxByOrNull { it.heat }   // keeps the first maximum, so ties go to the earliest hour
        val dayLevel = peak?.effective ?: HeatLevel.NORMAL
        val overRule = hours.filter { it.overTrigger }
        // The cut-off advises on heavy jobs, so it judges heavy work whatever the usual task,
        // keeping the worker's own clothing and acclimatisation.
        val heavy = profile.factors.copy(heavyWork = true)
        // Sun protection goes by the whole UV index, as BOM reports it, so 2.5 counts as 3.
        val sunHours = dayHours.filter { hour -> hour.uvForProtection?.let { HeatRules.needsSunProtection(it) } == true }
        val updated = matching?.takeIf { it.fetchedAt > 0 }
        return TodayHeat(
            date = today, site = profile.site, hours = hours,
            coverage = coverageOf(expected, covered, coverageEnd),
            uvCoverage = coverageOf(expectedDayHours, uvCovered, today.plusDays(1).atStartOfDay()),
            now = hours.firstOrNull { it.time == currentHour },
            peak = peak, dayLevel = dayLevel,
            bumped = peak != null && peak.effective != peak.base,
            crewRule = CrewRuleLine(profile.triggerC, overRule.firstOrNull()?.time?.toLocalTime(), overRule.size,
                hours.maxOfOrNull { it.airC }),
            heavyJobsBefore = hours.firstOrNull {
                HeatRules.effectiveLevel(it.base, heavy).atLeast(HeatLevel.HOT) || it.overTrigger
            }?.time?.toLocalTime(),
            uvPeak = dayHours.mapNotNull { it.uvForProtection }.maxOrNull()?.let(HeatRules::uvCategory),
            sunProtection = if (sunHours.isEmpty()) null
                else SunProtection(sunHours.first().time.toLocalTime(), sunHours.last().time.toLocalTime().plusHours(1)),
            suggestedCadence = HeatRules.suggestedCadence(dayLevel),
            freshness = freshness, updatedAtMillis = updated?.fetchedAt, refreshedBy = updated?.refreshedBy,
            error = matching?.error
        )
    }

    /**
     * Reads the usual or planned day's real interval using the same picked-time rules as Start shift, so a finish in
     * spring's missing hour does not hide the last work hour. Example: 00:00–02:30 on 4 October 2026 resolves to 03:30,
     * includes 00:00, 01:00 and 03:00, and never reports nonexistent 02:00 as missing. A backwards same-day range is empty.
     */
    private fun dayShiftHours(today: LocalDate, profile: HeatProfile): Pair<List<LocalDateTime>, LocalDateTime> {
        val start = ShiftRules.instant(today.atStartOfDay().plusMinutes(profile.shiftStartMinute.toLong()))
        val finish = today.atStartOfDay().plusMinutes(profile.shiftEndMinute.toLong())
        if (profile.shiftEndMinute <= profile.shiftStartMinute) return emptyList<LocalDateTime>() to finish
        val end = ShiftRules.instantAfter(finish, start)
        if (!end.isAfter(start)) return emptyList<LocalDateTime>() to ShiftRules.local(end)
        val hours = generateSequence(start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
            .takeWhile { it.isBefore(end) }
            .map(ShiftRules::local).distinct().toList()
        return hours to ShiftRules.local(end)
    }

    /**
     * An open shift's real hours, as wall-clock labels: from the hour holding its start to its planned finish, across
     * midnight, or on to the hour holding [now] once it runs past the finish. The autumn hour that happens twice is one
     * label and the spring hour that never happens is none. Coverage ends at the later of the finish and [now].
     */
    private fun spanHours(span: ShiftSpan, now: Instant): Pair<List<LocalDateTime>, LocalDateTime> {
        val end = maxOf(span.plannedEnd, now)
        val starts = generateSequence(span.start.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }
            .takeWhile { it.isBefore(end) || !it.isAfter(now) }
        return starts.map { it.atZone(MelbourneZone).toLocalDateTime() }.distinct().toList() to end.atZone(MelbourneZone).toLocalDateTime()
    }

    private fun coverageOf(expected: List<LocalDateTime>, covered: Set<LocalDateTime>, end: LocalDateTime): Coverage {
        val missing = expected.filterNot { it in covered }
        val prefixEnd = expected.takeWhile { it in covered }.lastOrNull()?.let { last ->
            // End the represented wall-clock hour, resolving a skipped spring hour and covering both repeated autumn hours.
            val hourEnd = last.plusHours(1).atZone(MelbourneZone).toLocalDateTime()
            minOf(hourEnd, end).toLocalTime()
        }
        return Coverage(complete = missing.isEmpty(), availableUntil = prefixEnd, missing = missing)
    }

    /**
     * Today's next step, shared by Today and the break sheet. [openAnswer] is `ShiftRules.openAnswer` for the open shift,
     * or null off shift. An open answer comes first and outlives a STALE or MISSING forecast; then a stop (Extreme, or a
     * heat number of 36° or more under the union table) says stop work; a rhythm is suggested only from a current
     * forecast on a Warm to Very hot day, from the table [rule] names.
     */
    fun nextStep(heat: TodayHeat, openAnswer: OpenAnswerKind?, rule: BreakRule = BreakRule.HEATSHIELD): NextStep {
        if (openAnswer != null) return NextStep.OpenAnswer(openAnswer)
        // The current hour is judged on its own (ShiftRules.stopNow), whatever break the shift's walk says is due.
        if (heat.dayLevel == HeatLevel.EXTREME || ShiftRules.stopNow(heat.now, BreakRule.HEATSHIELD)) return NextStep.StopHeavyWork
        if (rule == BreakRule.UNION_VTHC && (ShiftRules.stopNow(heat.now, rule) || ShiftRules.stopNow(heat.peak, rule))) return NextStep.StopHeavyWork
        val current = heat.freshness == Freshness.FRESH || heat.freshness == Freshness.AGING
        val cadence = dayCadence(heat, rule)?.takeIf { current } ?: return NextStep.None
        return NextStep.BreakSuggested(cadence)
    }

    /**
     * The day's rhythm from the break table [rule] names, the one Today's cadence line, the break sheet and the panel's
     * walk all use: HeatShield's for the day's level (null at Normal and Extreme), or the union table's row for the
     * peak's heat number (null below 30° and from 36°, where the union table stops work instead).
     */
    fun dayCadence(heat: TodayHeat, rule: BreakRule): Cadence? = when (rule) {
        BreakRule.HEATSHIELD -> heat.suggestedCadence
        BreakRule.UNION_VTHC -> heat.peak?.let { HeatRules.unionCadence(it.heat) }
    }

    /**
     * Every hour a running shift may read, from [forecast] for [profile]'s park: each valid hour of any date (a shift
     * runs past its planned finish and across midnight), through [profile]'s personal factors. Empty when the forecast
     * is another park's or [Freshness.MISSING]. A stale forecast still supplies the summary's heat; break timing goes
     * through [shiftTimingHours].
     */
    fun shiftHours(forecast: ForecastSnapshot?, profile: HeatProfile, nowMillis: Long): List<HourHeat> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(MelbourneZone).toLocalDate()
        val usable = forecast?.takeIf { it.site == profile.site && it.freshness(nowMillis, today) != Freshness.MISSING } ?: return emptyList()
        return usable.hours.filter { it.isValid }
            .mapNotNull { hour -> hour.localTime?.let { hourHeat(it, hour.temperature, hour.humidity, hour.windSpeed, hour.uv, hour.uvClearSky, profile) } }
            .distinctBy { it.time }
            .sortedBy { it.time }
    }

    /**
     * The hours a running shift times its breaks with: [shiftHours] of a current (FRESH or AGING) forecast. Stale or
     * missing hours are unknown, never cool, so nothing is timed from them (spec §5.13, Global Constraint 10).
     */
    fun shiftTimingHours(forecast: ForecastSnapshot?, profile: HeatProfile, nowMillis: Long): List<HourHeat> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(MelbourneZone).toLocalDate()
        val freshness = forecast?.takeIf { it.site == profile.site }?.freshness(nowMillis, today) ?: return emptyList()
        return if (freshness == Freshness.FRESH || freshness == Freshness.AGING) shiftHours(forecast, profile, nowMillis) else emptyList()
    }

    /**
     * The labelled 9 Jan 2026 sensor day through the same rules as Today. The sensor has no wind,
     * so still air (0 m/s) is assumed. Each shift hour keeps the reading with the highest heat number.
     * The hottest air can be a different reading, so the hour's crew-rule hatch checks every reading's
     * air on its own. The crew line counts sensor air up to the current reading. Both compare whole
     * degrees as shown, the same comparison as Today's `overTrigger`.
     */
    fun previewHeat(rows: List<SensorReading>, currentIndex: Int, profile: HeatProfile): PreviewHeat {
        require(rows.isNotEmpty()) { "The sensor day has no readings." }
        val index = currentIndex.mod(rows.size)
        val local = rows.map { SensorReplaySimulator.localDateTimeOf(it) }
        val firstShiftHour = profile.shiftStartMinute / 60
        fun inShift(i: Int) = local[i].toLocalDate() == SensorReplaySimulator.PREVIEW_DATE &&
            local[i].hour >= firstShiftHour && local[i].hour * 60 < profile.shiftEndMinute
        fun heatAt(time: LocalDateTime, reading: SensorReading) =
            hourHeat(time, reading.temperature, reading.humidity, 0.0, null, null, profile)
        // 37.8° air shows "38°", so it reaches a 38° rule; 37.4° shows "37°" and does not.
        fun airReachesRule(i: Int) = HeatRules.wholeDegrees(rows[i].temperature) >= profile.triggerC
        val dayHours = rows.indices.filter { inShift(it) }.groupBy { local[it].hour }.toSortedMap()
            .map { (hour, members) ->
                val time = SensorReplaySimulator.PREVIEW_DATE.atTime(hour, 0)
                members.map { heatAt(time, rows[it]) }.maxBy { it.heat }
                    .copy(overTrigger = members.any { airReachesRule(it) })
            }
        val soFar = rows.indices.filter { it <= index && inShift(it) }
        val passed = soFar.filter { airReachesRule(it) }
        val crewRule = CrewRuleLine(
            triggerC = profile.triggerC,
            firstHour = passed.firstOrNull()?.let { local[it].toLocalTime().truncatedTo(ChronoUnit.MINUTES) },
            hoursInShift = passed.map { local[it].hour }.distinct().size,
            peakAirC = soFar.maxOfOrNull { rows[it].temperature })
        return PreviewHeat(rows[index], heatAt(local[index], rows[index]), dayHours, dayHours.maxByOrNull { it.heat }, crewRule)
    }

    fun compare(context: WorkContext, forecast: ForecastSnapshot?, now: Long): List<WorkWindow> {
        if (!context.isValid || forecast == null || forecast.site != context.site || !forecast.isFresh(now)) return emptyList()
        if (forecast.hours.any { !it.isValid } || forecast.hours.map { it.time }.distinct().size != forecast.hours.size) return emptyList()
        val date = context.date
        val nowLocal = Instant.ofEpochMilli(now).atZone(MelbourneZone).toLocalDateTime()
        val byTime = forecast.hours.associateBy { it.localTime }
        val shiftStartMinutes = context.startHour * 60 + context.startMinute
        return (0..LAST_WINDOW_START_HOUR).mapNotNull { hour ->
            val start = date.atTime(hour, 0)
            // A window starts inside the shift, ends by the usual finish and lasts the two real hours its label says.
            if (hour * 60 < shiftStartMinutes || (hour + WINDOW_HOURS) * 60 > context.endMinute ||
                start.isBefore(nowLocal) || !spansTwoRealHours(start)) return@mapNotNull null
            val first = byTime[start] ?: return@mapNotNull null
            val second = byTime[start.plusHours(1)] ?: return@mapNotNull null
            val end = byTime[start.plusHours(2)] ?: return@mapNotNull null
            // These are point-in-time forecasts: require both boundaries and the intervening hour.
            val samples = listOf(first, second, end)
            val temp = samples.maxOf { it.temperature }
            val rh = samples.maxOf { it.humidity }
            val uv = samples.maxOf { it.uv }
            val load = effortWeight(context.effort) + if (context.clothing == "Heavy protective clothing") 1.0 else 0.0
            val score = samples.map { sample ->
                sample.temperature + (sample.humidity - 50).coerceAtLeast(0.0) * 0.06 +
                    sample.uv * 0.6 + (sample.temperature - 20).coerceAtLeast(0.0) * load * 0.15
            }.average()
            WorkWindow(String.format(Locale.ENGLISH, "%02d:00–%02d:00", hour, hour + WINDOW_HOURS),
                HeatRules.wholeDegrees(temp), rh.roundToInt(), HeatRules.uvCategory(uv),
                score, hour, hour + WINDOW_HOURS)
        }.sortedWith(compareBy<WorkWindow> { it.score }.thenBy { it.startHour })
    }

    /**
     * False when the window crosses a daylight-saving change. On 4 Oct 2026, 01:00–03:00 lasts one real hour and
     * 02:00 never happens; on 4 Apr 2027, 01:00–03:00 lasts three. Both ends must also share one UTC offset, which
     * drops 00:00–02:00 on 4 Oct, a window that ends in the skipped hour.
     */
    private fun spansTwoRealHours(start: LocalDateTime): Boolean {
        val from = start.atZone(MelbourneZone)
        val to = start.plusHours(WINDOW_HOURS.toLong()).atZone(MelbourneZone)
        return from.offset == to.offset && Duration.between(from, to) == Duration.ofHours(WINDOW_HOURS.toLong())
    }

    private fun effortWeight(effort: String): Double = when (effort) {
        "Heavy effort" -> 2.0
        "Moderate effort" -> 1.0
        else -> 0.0
    }
}


/** One moment of the labelled sensor-day preview. Never shown on Today. */
data class PreviewHeat(val reading: SensorReading, val current: HourHeat, val dayHours: List<HourHeat>,
                       val peak: HourHeat?, val crewRule: CrewRuleLine)
