package heatshield

import heatshield.data.ForecastHour
import heatshield.data.ForecastResponse
import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.data.ShiftPlan
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.UserPreferences
import heatshield.data.WeatherHours
import heatshield.data.WeatherRepository
import heatshield.data.WorkSession
import heatshield.domain.Cadence
import heatshield.domain.ContextReasoner
import heatshield.domain.Coverage
import heatshield.domain.CrewRuleLine
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.HeatRules
import heatshield.domain.PersonalFactors
import heatshield.domain.ShiftSpan
import heatshield.domain.SunProtection
import heatshield.domain.UvCategory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today's heat answer from a cached forecast, a worker profile and an injected clock. */
class ContextReasonerTest {
    private val date = LocalDate.of(2026, 1, 15)
    private val morning = date.atTime(9, 30)
    private val profile = HeatProfile("Birrarung Marr", "Irrigation & checks", 420, 900, 35.0, PersonalFactors())
    // Air °C by hour. Every [hour] is dry and windy, so the heat number equals air, and levels go by the whole
    // degrees shown (Normal < 29.5 ≤ Warm < 31.5 ≤ Hot < 34.5 ≤ Very hot < 37.5 ≤ Extreme):
    // 7:00 26° Normal; 8:00 29.9 → 30° Warm; 9:00 30.5 → 31° Warm; 10:00 32° Hot; 11:00 34° Hot; 12:00 36° Very hot;
    // 13:00 and 14:00 38.5 → 39° Extreme. 15:00 (39.5 → 40°) is the hottest hour of the day but falls after a
    // 7 am – 3 pm shift. Against the 35° rule, air shows 35° or more from 12:00.
    private val dayAir = mapOf(7 to 26.0, 8 to 29.9, 9 to 30.5, 10 to 32.0, 11 to 34.0, 12 to 36.0,
        13 to 38.5, 14 to 38.5, 15 to 39.5, 16 to 33.0)

    // Profile and single-hour reasoning.
    @Test
    fun profileOfTakesEffortFromTheTaskAndAcclimatisationFromTheStartOfToday() {
        val startOfToday = date.atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
        val startOfTomorrow = date.plusDays(1).atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
        assertEquals(HeatProfile("Birrarung Marr", "Digging & planting", 420, 900, 35.0, PersonalFactors(heavyWork = true)),
            ContextReasoner.profileOf(UserPreferences(ownerId = "local"), date))
        // An end at the start of tomorrow makes today the last acclimatising day.
        val preferences = UserPreferences(ownerId = "local", site = "Royal Park", effort = "Light effort",
            clothing = "Heavy protective clothing", task = "Push mowing", shiftStartMinute = 360,
            shiftEndMinute = 840, hotDayTrigger = 38.0, acclimatisingUntil = startOfTomorrow, setupDone = true)
        assertEquals(HeatProfile("Royal Park", "Push mowing", 360, 840, 38.0,
            PersonalFactors(heavyWork = true, heavyClothing = true, acclimatising = true)),
            ContextReasoner.profileOf(preferences, date))
        // Once setup explicitly confirms a task, effort follows that task.
        val light = preferences.copy(task = "Irrigation & checks", effort = "Heavy effort",
            clothing = "Standard workwear", acclimatisingUntil = null)
        assertEquals(PersonalFactors(), ContextReasoner.profileOf(light, date).factors)
        assertTrue(ContextReasoner.profileOf(preferences.copy(task = "Mowing"), date).factors.heavyWork)
        assertFalse(ContextReasoner.profileOf(preferences.copy(task = "Pruning & hedging"), date).factors.heavyWork)
        assertFalse(ContextReasoner.profileOf(preferences.copy(task = "Fence repairs"), date).factors.heavyWork)
        // The end is exclusive. Task 10 stores the start of day +7, so a flag ticked on 15 Jan is on from
        // 15 to 21 Jan and off from 22 Jan: seven days, not eight.
        val ticked = preferences.copy(acclimatisingUntil =
            date.plusDays(HeatRules.ACCLIMATISING_DAYS).atStartOfDay(MelbourneZone).toInstant().toEpochMilli())
        assertTrue(ContextReasoner.profileOf(ticked, date).factors.acclimatising)
        assertTrue(ContextReasoner.profileOf(ticked, date.plusDays(6)).factors.acclimatising)
        assertFalse(ContextReasoner.profileOf(ticked, date.plusDays(7)).factors.acclimatising)
        assertFalse(ContextReasoner.profileOf(preferences.copy(acclimatisingUntil = startOfToday), date).factors.acclimatising)
        assertFalse(ContextReasoner.profileOf(preferences.copy(acclimatisingUntil = startOfToday - 1), date).factors.acclimatising)
    }

    @Test
    fun unconfirmedMigratedTasksPreserveEachLegacyEffortUntilConfirmation() {
        listOf("Light effort", "Moderate effort", "Heavy effort").forEach { effort ->
            // Task 3 leaves all v1 rows in precisely this state, with the new task default unconfirmed.
            val migrated = UserPreferences(ownerId = "upgraded", effort = effort, setupDone = false)
            assertEquals(effort, ContextReasoner.usualEffort(migrated).label)
            assertEquals(effort == "Heavy effort", ContextReasoner.profileOf(migrated, date).factors.heavyWork)
            val confirmed = migrated.copy(task = "Irrigation & checks", effort = "Light effort", setupDone = true)
            assertEquals("Light effort", ContextReasoner.usualEffort(confirmed).label)
            assertFalse(ContextReasoner.profileOf(confirmed, date).factors.heavyWork)
            val heavy = migrated.copy(task = "Push mowing", effort = "Heavy effort", setupDone = true)
            assertTrue(ContextReasoner.profileOf(heavy, date).factors.heavyWork)
        }
    }

    // One source for today's work: Today, Break and Plan all read effectiveProfile.
    @Test
    fun effectiveProfileTakesTodaysPlanForTheWorkAndKeepsTheWorkersOwnRuleAndAcclimatisation() {
        val todayMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()   // a plan stores its date at UTC midnight
        val usualLight = UserPreferences(ownerId = "local", site = "Birrarung Marr", task = "Irrigation & checks",
            effort = "Light effort", clothing = "Standard workwear", hotDayTrigger = 38.0, setupDone = true)
        val heavyPlan = ShiftPlan(ownerId = "local", work = "Push mowing", effort = "Heavy effort",
            clothing = "Heavy protective clothing", dayMillis = todayMillis, startHour = 6, startMinute = 30,
            site = "Royal Park", endMinute = 13 * 60)
        // Today's plan sets the park, work, effort, clothing and hours; the 38° crew rule stays the worker's own.
        assertEquals(HeatProfile("Royal Park", "Push mowing", 390, 780, 38.0,
            PersonalFactors(heavyWork = true, heavyClothing = true)),
            ContextReasoner.effectiveProfile(usualLight, heavyPlan, date))
        // So does acclimatisation.
        val newStarter = usualLight.copy(acclimatisingUntil = date.plusDays(1).atStartOfDay(MelbourneZone).toInstant().toEpochMilli())
        assertTrue(ContextReasoner.effectiveProfile(newStarter, heavyPlan, date).factors.acclimatising)
        // Effort is the plan's saved effort label, not its work name; an unknown label reads as moderate.
        assertFalse(ContextReasoner.effectiveProfile(usualLight, heavyPlan.copy(effort = "Moderate effort"), date).factors.heavyWork)
        assertFalse(ContextReasoner.effectiveProfile(usualLight, heavyPlan.copy(effort = "Unknown"), date).factors.heavyWork)

        // A Warm day: air 31.0 shows 31° all day, Warm. Usual light work in workwear stays Warm; the planned heavy
        // work in protective clothing adds two steps, Very hot.
        val warm = fresh((5..18).map { h -> hour(date.atTime(h, 0), 31.0) })
        assertEquals(HeatLevel.WARM, reason(warm, ContextReasoner.effectiveProfile(usualLight, null, date)).dayLevel)
        assertEquals(HeatLevel.VERY_HOT, reason(warm.copy(site = heavyPlan.site), ContextReasoner.effectiveProfile(usualLight, heavyPlan, date)).dayLevel)
        // The reverse: usual heavy work in protective clothing, with a plan for light work in workwear today.
        val usualHeavy = usualLight.copy(task = "Digging & planting", effort = "Heavy effort", clothing = "Heavy protective clothing")
        val lightPlan = heavyPlan.copy(work = "Irrigation & checks", effort = "Light effort", clothing = "Standard workwear")
        assertEquals(HeatLevel.VERY_HOT, reason(warm, ContextReasoner.effectiveProfile(usualHeavy, null, date)).dayLevel)
        assertEquals(PersonalFactors(), ContextReasoner.effectiveProfile(usualHeavy, lightPlan, date).factors)
        assertEquals(HeatLevel.WARM, reason(warm.copy(site = lightPlan.site), ContextReasoner.effectiveProfile(usualHeavy, lightPlan, date)).dayLevel)

        // A plan for another day leaves today on the usual work.
        val usual = ContextReasoner.profileOf(usualLight, date)
        val tomorrowPlan = heavyPlan.copy(dayMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val yesterdayPlan = heavyPlan.copy(dayMillis = date.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        assertEquals(usual, ContextReasoner.effectiveProfile(usualLight, tomorrowPlan, date))
        assertEquals(usual, ContextReasoner.effectiveProfile(usualLight, yesterdayPlan, date))
        assertEquals(usual, ContextReasoner.effectiveProfile(usualLight, null, date))
        assertEquals(usual, ContextReasoner.effectiveProfile(usualLight, heavyPlan.copy(ownerId = "other-owner"), date))
    }

    @Test
    fun anOpenShiftSuppliesTodaysWorkConditionsAndItsHours() {
        val prefs = UserPreferences(ownerId = "local", site = "Royal Park", task = "Irrigation & checks", effort = "Light effort", setupDone = true)
        val startedAt = date.atTime(6, 40).atZone(MelbourneZone).toInstant().toEpochMilli()
        val shift = WorkSession(id = 3, ownerId = "local", title = "Push mowing", site = "Carlton Gardens", date = "15 Jan 2026", outdoorMinutes = 0,
            breakMinutes = 0, status = ShiftStatus.NONE, startedAt = startedAt, plannedEndAt = startedAt + 7 * 3_600_000L, source = ShiftSource.TRACKED,
            heavyWork = true, heavyClothing = true)
        val profile = ContextReasoner.effectiveProfile(prefs, null, date, shift)
        assertEquals(HeatProfile("Carlton Gardens", "Push mowing", 400, 820, 35.0, PersonalFactors(heavyWork = true, heavyClothing = true),
            ShiftSpan(Instant.ofEpochMilli(startedAt), Instant.ofEpochMilli(startedAt + 7 * 3_600_000L))), profile)
        // A closed shift, or another owner's, changes nothing.
        assertEquals(ContextReasoner.profileOf(prefs, date), ContextReasoner.effectiveProfile(prefs, null, date, shift.copy(endedAt = startedAt + 1)))
        assertEquals(ContextReasoner.profileOf(prefs, date), ContextReasoner.effectiveProfile(prefs, null, date, shift.copy(ownerId = "uid")))
        // A shift planned past midnight ends the profile at 23:59 today.
        assertEquals(1439, ContextReasoner.effectiveProfile(prefs, null, date, shift.copy(plannedEndAt = startedAt + 20 * 3_600_000L)).shiftEndMinute)
    }

    // A running shift reads every hour of its park's forecast: past its planned finish (it keeps running until ended)
    // and across midnight. Break timing takes them from a current forecast only; the summary from any saved one.
    @Test
    fun shiftHoursRunPastThePlannedFinishAndAcrossMidnight() {
        val forecast = fresh(hotDay() + hotDay(date.plusDays(1)))
        val shift = ContextReasoner.shiftHours(forecast, profile, millisAt(morning))
        assertTrue("16:00, after a 7 am – 3 pm shift", shift.any { it.time == date.atTime(16, 0) })
        assertTrue("1 am tomorrow, for a night shift", shift.any { it.time == date.plusDays(1).atTime(1, 0) })
        assertEquals(48, shift.size)
        assertEquals(shift.sortedBy { it.time }, shift)
        // Through the shift's own factors: heavy work takes 16:00 (33°) from Hot to Very hot.
        val heavy = ContextReasoner.shiftHours(forecast, profile.copy(factors = PersonalFactors(heavyWork = true)), millisAt(morning))
        assertEquals(HeatLevel.VERY_HOT, heavy.single { it.time == date.atTime(16, 0) }.effective)
        assertEquals(ContextReasoner.shiftHours(forecast, profile, millisAt(morning)), ContextReasoner.shiftTimingHours(forecast, profile, millisAt(morning)))
        // Another park's forecast, or none, gives no hours.
        assertTrue(ContextReasoner.shiftHours(forecast, profile.copy(site = "Royal Park"), millisAt(morning)).isEmpty())
        assertTrue(ContextReasoner.shiftHours(null, profile, millisAt(morning)).isEmpty())
        // Seven hours old: the summary may still read it, break timing may not (Global Constraint 10).
        val stale = snapshot(hotDay() + hotDay(date.plusDays(1)), millisAt(morning) - 7 * 3_600_000L)
        assertEquals(48, ContextReasoner.shiftHours(stale, profile, millisAt(morning)).size)
        assertTrue(ContextReasoner.shiftTimingHours(stale, profile, millisAt(morning)).isEmpty())
    }

    // Astra M2 #4: Today and the break sheet read an open shift's own span, across midnight, and the hour it is now in
    // overtime. At 1:20 am a 22:00–06:00 shift is in its 1 am hour, which is Extreme.
    @Test
    fun anOpenNightShiftReadsItsOwnHoursAcrossMidnightAndItsOvertimeHour() {
        val tomorrow = date.plusDays(1)
        val prefs = UserPreferences(ownerId = "local", site = "Birrarung Marr", task = "Irrigation & checks", effort = "Light effort", setupDone = true)
        val night = WorkSession(id = 3, ownerId = "local", title = "Irrigation & checks", site = "Birrarung Marr", date = "15 Jan 2026", outdoorMinutes = 0,
            breakMinutes = 0, status = ShiftStatus.NONE, startedAt = millisAt(date.atTime(22, 0)), plannedEndAt = millisAt(tomorrow.atTime(6, 0)),
            source = ShiftSource.TRACKED)
        val nightAir = (hotDay() + hotDay(tomorrow)).map { hour ->
            if (LocalDateTime.parse(hour.time) == tomorrow.atTime(1, 0)) hour.copy(temperature = 39.0) else hour.copy(temperature = 25.0)
        }
        val at0120 = tomorrow.atTime(1, 20)
        val heat = ContextReasoner.todayHeat(snapshot(nightAir, millisAt(at0120) - 10 * 60_000L),
            ContextReasoner.effectiveProfile(prefs, null, tomorrow, night), clockAt(at0120))
        assertEquals(listOf(date.atTime(22, 0), date.atTime(23, 0)) + (0..5).map { tomorrow.atTime(it, 0) }, heat.hours.map { it.time })
        assertEquals(tomorrow.atTime(1, 0), heat.now?.time)
        assertEquals(HeatLevel.EXTREME, heat.dayLevel)
        assertTrue(heat.coverage.complete)
        assertEquals(heatshield.domain.NextStep.StopHeavyWork, ContextReasoner.nextStep(heat, null))
        // A gap after midnight is missing with its own date, so the ribbon places it after the evening's hours.
        val gap = ContextReasoner.todayHeat(snapshot(nightAir.filterNot { LocalDateTime.parse(it.time) == tomorrow.atTime(2, 0) }, millisAt(at0120) - 10 * 60_000L),
            ContextReasoner.effectiveProfile(prefs, null, tomorrow, night), clockAt(at0120))
        assertEquals(Coverage(complete = false, availableUntil = LocalTime.of(2, 0), missing = listOf(tomorrow.atTime(2, 0))), gap.coverage)
        // Overtime: at 15:20 a 7:00–15:00 shift is in its 3 pm hour.
        val day = night.copy(startedAt = millisAt(date.atTime(7, 0)), plannedEndAt = millisAt(date.atTime(15, 0)))
        val overtime = ContextReasoner.todayHeat(snapshot(hotDay(), millisAt(date.atTime(15, 0))),
            ContextReasoner.effectiveProfile(prefs, null, date, day), clockAt(date.atTime(15, 20)))
        assertEquals((7..15).toList(), overtime.hours.map { it.time.hour })
        assertEquals(date.atTime(15, 0), overtime.now?.time)
    }

    @Test
    fun hourHeatAgreesWithHeatRulesAndKeepsTheRuleOnAirTemperature() {
        val bumped = profile.copy(factors = PersonalFactors(heavyWork = true, heavyClothing = true))
        val time = date.atTime(13, 0)
        listOf(Triple(35.0, 40.0, 3.0), Triple(40.0, 10.0, 8.0), Triple(34.0, 70.0, 0.0), Triple(29.0, 50.0, 1.0),
            Triple(31.0, 30.0, 2.0)).forEach { (air, humidity, wind) ->
            val heat = ContextReasoner.hourHeat(time, air, humidity, wind, 5.0, null, bumped)
            val base = HeatRules.baseLevel(HeatRules.heatNumber(air, humidity, wind))
            assertEquals(time, heat.time)
            assertEquals(HeatRules.apparentTemperature(air, humidity, wind), heat.feelsLikeC, 1e-9)
            assertEquals(HeatRules.heatNumber(air, humidity, wind), heat.heat, 1e-9)
            assertEquals(base, heat.base)
            assertEquals(HeatRules.effectiveLevel(base, bumped.factors), heat.effective)
            assertEquals(HeatRules.wholeDegrees(air) >= 35, heat.overTrigger)
        }
        // Humid still air: the heat number is Extreme, yet air stays under the 35° rule.
        val humid = ContextReasoner.hourHeat(time, 34.0, 70.0, 0.0, 5.0, null, profile)
        assertEquals(HeatLevel.EXTREME, humid.base)
        assertFalse(humid.overTrigger)
        assertTrue(ContextReasoner.hourHeat(time, 35.0, 10.0, 8.0, 5.0, null, profile).overTrigger)
        // The rule compares the whole degrees a screen shows: 34.5° shows 35° and reaches a 35° rule; 34.4° shows 34°.
        assertTrue(ContextReasoner.hourHeat(time, 34.5, 10.0, 8.0, 5.0, null, profile).overTrigger)
        assertFalse(ContextReasoner.hourHeat(time, 34.4, 10.0, 8.0, 5.0, null, profile).overTrigger)
        assertEquals(7.0, ContextReasoner.hourHeat(time, 30.0, 40.0, 2.0, 5.0, 7.0, profile).uvForProtection!!, 0.0)
        assertEquals(5.0, ContextReasoner.hourHeat(time, 30.0, 40.0, 2.0, 5.0, null, profile).uvForProtection!!, 0.0)
        assertNull(ContextReasoner.hourHeat(time, 30.0, 40.0, 2.0, null, null, profile).uvForProtection)
    }

    // todayHeat: the shift, the peak and the lines Today shows.
    @Test
    fun todayKeepsOnlyValidHoursDatedTodayInsideTheShift() {
        val forecast = fresh(hotDay() + hotDay(date.plusDays(1)))
        val heat = reason(forecast)
        assertEquals(date, heat.date)
        assertEquals("Birrarung Marr", heat.site)
        assertEquals(Freshness.FRESH, heat.freshness)
        assertEquals((7..14).toList(), heat.hours.map { it.time.hour })
        assertTrue(heat.hours.all { it.time.toLocalDate() == date })
        // The start is floored to its hour and the finish is exclusive.
        assertEquals((7..14).toList(), reason(forecast, profile.copy(shiftStartMinute = 450)).hours.map { it.time.hour })
        assertEquals((7..15).toList(), reason(forecast, profile.copy(shiftEndMinute = 930)).hours.map { it.time.hour })
        assertEquals((6..12).toList(),
            reason(forecast, profile.copy(shiftStartMinute = 360, shiftEndMinute = 780)).hours.map { it.time.hour })
        val broken = fresh(hotDay().map { if (LocalDateTime.parse(it.time).hour == 10) it.copy(temperature = Double.NaN) else it })
        assertEquals(listOf(7, 8, 9, 11, 12, 13, 14), reason(broken).hours.map { it.time.hour })
        // "Now" is the shift hour holding the clock; after the shift there is none.
        assertEquals(date.atTime(9, 0), heat.now?.time)
        assertNull(reason(forecast, at = date.atTime(16, 30)).now)
    }

    @Test
    fun peakIsTheHottestShiftHourWithTiesGoingToTheEarliest() {
        val heat = reason(fresh(hotDay()))
        assertEquals(date.atTime(13, 0), heat.peak?.time)
        assertEquals(38.5, heat.peak!!.heat, 1e-9)
        assertEquals(HeatLevel.EXTREME, heat.dayLevel)
        assertFalse(heat.bumped)
        // Extreme has no work/rest split; Today shows the stop-heavy-work line instead (Task 8's NextStep.StopHeavyWork).
        assertNull(heat.suggestedCadence)
    }

    @Test
    fun bumpedLevelAndCadenceFollowPersonalFactorsAtThePeak() {
        val hot = fresh(shiftOf { h -> if (h == 12) 33.0 else 31.0 })
        val plain = reason(hot)
        assertEquals(date.atTime(12, 0), plain.peak?.time)
        assertEquals(HeatLevel.HOT, plain.dayLevel)
        assertFalse(plain.bumped)
        assertEquals(Cadence(45, 15), plain.suggestedCadence)

        val heavy = reason(hot, profile.copy(factors = PersonalFactors(heavyWork = true)))
        assertEquals(HeatLevel.HOT, heavy.peak?.base)
        assertEquals(HeatLevel.VERY_HOT, heavy.dayLevel)
        assertTrue(heavy.bumped)
        assertEquals(Cadence(30, 15), heavy.suggestedCadence)

        // Three factors still lift Warm by only two levels.
        val warm = fresh(shiftOf { 31.0 })
        assertEquals(Cadence(60, 10), reason(warm).suggestedCadence)
        val all = PersonalFactors(heavyWork = true, heavyClothing = true, acclimatising = true)
        assertEquals(HeatLevel.VERY_HOT, reason(warm, profile.copy(factors = all)).dayLevel)
        val bumpedToExtreme = reason(hot, profile.copy(factors = all))
        assertEquals(HeatLevel.EXTREME, bumpedToExtreme.dayLevel)
        assertNull(bumpedToExtreme.suggestedCadence)

        // A Normal day stays quiet whatever the factors.
        val cool = reason(fresh(shiftOf { 25.0 }), profile.copy(factors = all))
        assertEquals(HeatLevel.NORMAL, cool.dayLevel)
        assertFalse(cool.bumped)
        assertNull(cool.suggestedCadence)
    }

    @Test
    fun crewRuleCountsShiftHoursWhereAirReachesTheTrigger() {
        // Shift air shows 26, 30, 31, 32, 34, 36, 39, 39 degrees (see dayAir).
        assertEquals(CrewRuleLine(35.0, LocalTime.of(12, 0), 3, 38.5), reason(fresh(hotDay())).crewRule)
        assertEquals(CrewRuleLine(38.0, LocalTime.of(13, 0), 2, 38.5),
            reason(fresh(hotDay()), profile.copy(triggerC = 38.0)).crewRule)
        // 38.5° shows as 39°, so a 39° rule counts 13:00 and 14:00; nothing shows 40°.
        assertEquals(CrewRuleLine(39.0, LocalTime.of(13, 0), 2, 38.5),
            reason(fresh(hotDay()), profile.copy(triggerC = 39.0)).crewRule)
        assertEquals(CrewRuleLine(40.0, null, 0, 38.5), reason(fresh(hotDay()), profile.copy(triggerC = 40.0)).crewRule)
        // Humid still air feels far hotter, but the crew rule reads air temperature only.
        val sticky = reason(fresh((7..14).map { h -> hour(date.atTime(h, 0), 34.0, humidity = 70.0, wind = 0.0) }))
        assertEquals(HeatLevel.EXTREME, sticky.dayLevel)
        assertEquals(CrewRuleLine(35.0, null, 0, 34.0), sticky.crewRule)
    }

    @Test
    fun heavyJobsCutOffIsTheFirstHourHotForHeavyWorkOrOverTheRule() {
        // The line advises on heavy jobs, so a light usual task gets the same cut-off as a heavy one.
        // 8:00 air 29.9 shows 30°, Warm, and heavy work lifts it to Hot; the 7:00 hour (26°) is Normal and gets no bump.
        assertEquals(LocalTime.of(8, 0), reason(fresh(hotDay())).heavyJobsBefore)
        assertEquals(LocalTime.of(8, 0),
            reason(fresh(hotDay()), profile.copy(factors = PersonalFactors(heavyWork = true))).heavyJobsBefore)
        // A Warm shift (31° shown) stays Warm for this light-task worker, yet heavy work is Hot from the first hour.
        val warm = reason(fresh(shiftOf { 31.0 }))
        assertEquals(HeatLevel.WARM, warm.dayLevel)
        assertEquals(LocalTime.of(7, 0), warm.heavyJobsBefore)
        // Air reaching a low custom rule counts even while the level is below Hot: 7:00 air 26.0 shows 26°, Normal
        // even for heavy work, but it reaches a 26° rule, so the cut-off moves from 08:00 to 07:00.
        assertEquals(LocalTime.of(7, 0), reason(fresh(hotDay()), profile.copy(triggerC = 26.0)).heavyJobsBefore)
        // Already Hot when the shift starts.
        val humid = fresh((7..14).map { h -> hour(date.atTime(h, 0), 34.0, humidity = 70.0, wind = 0.0) })
        assertEquals(LocalTime.of(7, 0), reason(humid).heavyJobsBefore)
        // Never Hot and never over the rule.
        assertNull(reason(fresh(shiftOf { 25.0 })).heavyJobsBefore)
    }

    @Test
    fun uvPeakAndSunProtectionUseClearSkyThenFallBackToAllSky() {
        // (hour, uv_index, uv_index_clear_sky)
        val uv: List<Triple<Int, Double, Double?>> = listOf(
            Triple(6, 0.5, 1.0), Triple(7, 1.0, 2.9), Triple(8, 2.0, 3.0), Triple(9, 2.5, 6.0),
            Triple(10, 5.0, 8.4), Triple(11, 5.0, 8.4), Triple(12, 5.0, 8.4), Triple(13, 5.0, 8.4),
            Triple(14, 7.0, null), Triple(15, 3.0, null), Triple(16, 2.9, null), Triple(17, 4.0, 1.0),
            Triple(18, 0.0, null))
        val heat = reason(fresh(uv.map { (h, allSky, clearSky) -> hour(date.atTime(h, 0), 25.0, uv = allSky, uvClearSky = clearSky) }))
        // UV is judged on the whole index. Clear-sky 2.9 at 07:00 and all-sky 2.9 at 16:00 both round to 3, so
        // protection runs 07:00–17:00. 17:00's clear-sky 1.0 wins over its all-sky 4.0, so it does not count.
        // UV covers the whole day, not only the shift: 15:00 and 16:00 are after this 7 am – 3 pm shift.
        assertEquals(SunProtection(LocalTime.of(7, 0), LocalTime.of(17, 0)), heat.sunProtection)
        // The highest UV for protection is clear-sky 8.4, which rounds to 8: Very high.
        assertEquals(UvCategory.VERY_HIGH, heat.uvPeak)
        val low = reason(fresh((6..18).map { h -> hour(date.atTime(h, 0), 25.0, uv = 2.0) }))
        assertEquals(UvCategory.LOW, low.uvPeak)
        assertNull(low.sunProtection)
        // 2.5 rounds to UV 3 and needs protection; 2.4 rounds to 2 and does not.
        val edge = reason(fresh((6..18).map { h -> hour(date.atTime(h, 0), 25.0, uv = if (h == 12) 2.5 else 2.4) }))
        assertEquals(UvCategory.MODERATE, edge.uvPeak)
        assertEquals(SunProtection(LocalTime.of(12, 0), LocalTime.of(13, 0)), edge.sunProtection)
    }

    @Test
    fun updateStampAndAgeComeFromTheSnapshot() {
        val fetched = millisAt(morning) - 2 * 60 * 60_000L
        val background = snapshot(hotDay(), fetched).copy(refreshedBy = ForecastSnapshot.REFRESHED_BY_BACKGROUND, error = "Timeout")
        val heat = reason(background)
        assertEquals(Freshness.AGING, heat.freshness)
        assertEquals(fetched, heat.updatedAtMillis)
        assertEquals("background", heat.refreshedBy)
        assertEquals("Timeout", heat.error)
        assertEquals((7..14).toList(), heat.hours.map { it.time.hour })
        // Older than 6 h: still today's hours, flagged STALE so Today says how old the forecast is and offers Retry.
        val old = reason(snapshot(hotDay(), millisAt(morning) - 7 * 60 * 60_000L))
        assertEquals(Freshness.STALE, old.freshness)
        assertEquals(date.atTime(13, 0), old.peak?.time)
    }

    // Review Focus 1: a forecast that doesn't cover today, a DST day, and a shift past the last hour.
    @Test
    fun forecastWithOnlyTomorrowIsStaleAndNeverShowsTomorrowsPeak() {
        val heat = reason(fresh(hotDay(date.plusDays(1))))
        assertEquals(Freshness.STALE, heat.freshness)
        assertTrue(heat.hours.isEmpty())
        assertNull(heat.peak)
        assertNull(heat.now)
        assertEquals(HeatLevel.NORMAL, heat.dayLevel)
        assertEquals(CrewRuleLine(35.0, null, 0, null), heat.crewRule)
        assertNull(heat.heavyJobsBefore)
        assertNull(heat.uvPeak)
        assertNull(heat.sunProtection)
        // A cache fetched yesterday whose hours end yesterday is stale too.
        val yesterday = reason(snapshot(hotDay(date.minusDays(1)), millisAt(date.minusDays(1).atTime(18, 0))))
        assertEquals(Freshness.STALE, yesterday.freshness)
        assertTrue(yesterday.hours.isEmpty())
    }

    @Test
    fun daylightSavingDayWithoutTwoAmKeepsRealHoursAndDoesNotCrash() {
        val dstDay = LocalDate.of(2026, 10, 4)   // Melbourne clocks jump from 02:00 to 03:00
        // Fetched at 01:45, just before the change, so every label is at +10 (see dstStartHours).
        val beforeChange = snapshot(dstStartHours(), millisAt(dstDay.atTime(1, 45)))
        val early = ContextReasoner.todayHeat(beforeChange, profile.copy(shiftStartMinute = 0, shiftEndMinute = 360),
            clockAt(dstDay.atTime(3, 30)))
        assertEquals(dstDay, early.date)
        assertEquals(Freshness.FRESH, early.freshness)
        assertEquals(listOf(0, 1, 3, 4, 5), early.hours.map { it.time.hour })
        // 02:00 never happens on this day, so it is not a missing hour: the 00:00–06:00 shift is fully covered.
        assertEquals(Coverage(complete = true, availableUntil = LocalTime.of(6, 0), missing = emptyList()), early.coverage)
        assertEquals(dstDay.atTime(3, 0), early.now?.time)
        assertEquals(CrewRuleLine(35.0, null, 0, 15.0), early.crewRule)
        // At a 06:30 muster the same cache is aging and still covers the real shift hours.
        val muster = ContextReasoner.todayHeat(beforeChange, profile, clockAt(dstDay.atTime(6, 30)))
        assertEquals(Freshness.AGING, muster.freshness)
        assertEquals((7..14).toList(), muster.hours.map { it.time.hour })
        assertEquals(SunProtection(LocalTime.of(9, 0), LocalTime.of(16, 0)), muster.sunProtection)
        assertEquals(UvCategory.MODERATE, muster.uvPeak)
        // At 14:30 "now" is the reading Open-Meteo labelled 13:00, an hour behind the clock.
        val afternoon = ContextReasoner.todayHeat(beforeChange, profile, clockAt(dstDay.atTime(14, 30)))
        assertEquals(Freshness.STALE, afternoon.freshness)
        assertEquals(dstDay.atTime(14, 0), afternoon.now?.time)
        assertEquals(dstLabelAir(13), afternoon.now!!.airC, 1e-9)
    }

    @Test
    fun springGapFinishMatchesTheConfirmedDefaultShift() {
        val day = LocalDate.of(2026, 10, 4)
        val clock = clockAt(day.atTime(0, 30))
        val forecast = snapshot(listOf(0, 1, 3).map {
            hour(day.atTime(it, 0), if (it == 3) 33.0 else 31.0, humidity = 20.0, wind = 1.0)
        }, clock.millis())
        val usual = profile.copy(shiftStartMinute = 0, shiftEndMinute = 150)
        val before = ContextReasoner.todayHeat(forecast, usual, clock)
        val start = clock.instant()
        val finish = heatshield.domain.ShiftRules.instantAfter(heatshield.domain.ShiftRules.defaultFinish(start, 0, 150), start)
        val confirmed = ContextReasoner.todayHeat(forecast, usual.copy(shift = ShiftSpan(start, finish)), clock)
        assertEquals(120L, java.time.Duration.between(start, finish).toMinutes())
        assertEquals(listOf(0, 1, 3), before.hours.map { it.time.hour })
        assertEquals(confirmed.hours, before.hours)
        assertEquals(Coverage(true, LocalTime.of(3, 30), emptyList()), before.coverage)
        assertEquals(confirmed.coverage, before.coverage)
        assertEquals(33.0, requireNotNull(before.peak).heat, 1e-9)
        assertEquals(HeatLevel.HOT, before.dayLevel)
        assertEquals(Cadence(45, 15), before.suggestedCadence)
        assertEquals(confirmed.suggestedCadence, before.suggestedCadence)

        val missingFinishHour = ContextReasoner.todayHeat(forecast.copy(hours = forecast.hours.dropLast(1)), usual, clock)
        assertEquals(Coverage(false, LocalTime.of(3, 0), listOf(day.atTime(3, 0))), missingFinishHour.coverage)
    }

    @Test
    fun coveragePrefixAfterTheRepeatedAutumnHourEndsAtThreeAm() {
        val autumn = LocalDate.of(2027, 4, 4)
        val clock = clockAt(autumn.atTime(4, 30))
        val earlyHours = (0..2).map { hour(autumn.atTime(it, 0), 22.0) }
        val cached = snapshot(earlyHours, clock.millis() - 60_000)
        val heat = ContextReasoner.todayHeat(cached, profile.copy(shiftStartMinute = 0, shiftEndMinute = 360), clock)
        assertEquals(Coverage(false, LocalTime.of(3, 0), (3..5).map { autumn.atTime(it, 0) }), heat.coverage)
    }

    @Test
    fun shiftRunningPastTheLastForecastHourGetsAPartialDay() {
        val heat = reason(fresh(hotDay().filter { LocalDateTime.parse(it.time).hour <= 11 }))
        assertEquals(Freshness.FRESH, heat.freshness)
        assertEquals((7..11).toList(), heat.hours.map { it.time.hour })
        assertEquals(date.atTime(11, 0), heat.peak?.time)
        assertEquals(CrewRuleLine(35.0, null, 0, 34.0), heat.crewRule)
        // Fresh, yet partial: Today labels its conclusions as based on available hours and reports missing shift hours.
        // The continuous prefix ends at noon; it says nothing about the missing afternoon.
        assertEquals(Coverage(complete = false, availableUntil = LocalTime.of(12, 0),
            missing = (12..14).map { date.atTime(it, 0) }), heat.coverage)
    }

    @Test
    fun coverageListsEveryShiftHourWithoutAForecastWhateverItsAge() {
        // Every shift hour 07:00–14:00 has a forecast, available until the end of the 14:00 hour.
        assertEquals(Coverage(complete = true, availableUntil = LocalTime.of(15, 0), missing = emptyList()),
            reason(fresh(hotDay())).coverage)
        // A gap in the middle: 10:00 and 11:00 are missing. Later data cannot extend the continuously covered prefix.
        val gap = reason(fresh(hotDay().filter { LocalDateTime.parse(it.time).hour !in 10..11 }))
        assertEquals(listOf(7, 8, 9, 12, 13, 14), gap.hours.map { it.time.hour })
        assertEquals(Coverage(complete = false, availableUntil = LocalTime.of(10, 0),
            missing = listOf(date.atTime(10, 0), date.atTime(11, 0))), gap.coverage)
        val leadingGap = reason(fresh(hotDay().filter { LocalDateTime.parse(it.time).hour != 7 }))
        assertNull(leadingGap.coverage.availableUntil)
        assertEquals(listOf(date.atTime(7, 0)), leadingGap.coverage.missing)
        // Coverage ends at the actual finish even when the last represented hour is only partly worked.
        assertEquals(LocalTime.of(14, 30), reason(fresh(hotDay()), profile.copy(shiftEndMinute = 870)).coverage.availableUntil)
        // An invalid hour is a missing hour too.
        val broken = fresh(hotDay().map { if (LocalDateTime.parse(it.time).hour == 10) it.copy(temperature = Double.NaN) else it })
        assertEquals(listOf(date.atTime(10, 0)), reason(broken).coverage.missing)
        // No forecast at all, or one with nothing for today: every shift hour is missing and nothing is available.
        val nothing = Coverage(complete = false, availableUntil = null, missing = (7..14).map { date.atTime(it, 0) })
        assertEquals(nothing, reason(null).coverage)
        assertEquals(nothing, reason(fresh(hotDay(date.plusDays(1)))).coverage)
        // Coverage is not freshness: a 7-hour-old forecast is STALE but can still cover the whole shift.
        val old = reason(snapshot(hotDay(), millisAt(morning) - 7 * 60 * 60_000L))
        assertEquals(Freshness.STALE, old.freshness)
        assertTrue(old.coverage.complete)
    }

    @Test
    fun uvCoverageUsesTheWholeDayAndNeverBorrowsShiftCoverage() {
        val full = reason(fresh(hotDay()))
        assertTrue(full.coverage.complete)
        assertTrue(full.uvCoverage.complete)
        assertEquals(LocalTime.MIDNIGHT, full.uvCoverage.availableUntil) // end of this calendar day
        val afterShiftMissing = reason(fresh(hotDay().filter { LocalDateTime.parse(it.time).hour != 18 }))
        assertTrue(afterShiftMissing.coverage.complete)
        assertFalse(afterShiftMissing.uvCoverage.complete)
        assertEquals(listOf(date.atTime(18, 0)), afterShiftMissing.uvCoverage.missing)
        assertEquals(LocalTime.of(18, 0), afterShiftMissing.uvCoverage.availableUntil)
        // M1 merges required heat and UV fields into one point: an in-shift missing point affects both ranges.
        val duringShiftMissing = reason(fresh(hotDay().filter { LocalDateTime.parse(it.time).hour != 14 }))
        assertFalse(duringShiftMissing.coverage.complete)
        assertFalse(duringShiftMissing.uvCoverage.complete)
        assertEquals(listOf(date.atTime(14, 0)), duringShiftMissing.coverage.missing)
        assertEquals(listOf(date.atTime(14, 0)), duringShiftMissing.uvCoverage.missing)
        val shiftOnly = reason(fresh(shiftOf { 25.0 }))
        assertTrue(shiftOnly.coverage.complete)
        assertFalse(shiftOnly.uvCoverage.complete)
        assertNull(shiftOnly.uvCoverage.availableUntil)
        assertEquals((0..23).filter { it !in 7..14 }.map { date.atTime(it, 0) }, shiftOnly.uvCoverage.missing)
        val missing = reason(null)
        assertEquals((0..23).map { date.atTime(it, 0) }, missing.uvCoverage.missing)
    }

    @Test
    fun anotherSitesForecastCannotSupplyTheSelectedSitesHeatOrUpdateStamp() {
        val other = fresh(hotDay()).copy(site = "Royal Park", error = "Royal Park request failed")
        val heat = reason(other)
        assertEquals(Freshness.MISSING, heat.freshness)
        assertTrue(heat.hours.isEmpty())
        assertNull(heat.peak)
        assertNull(heat.updatedAtMillis)
        assertNull(heat.error)
        assertEquals(profile.site, heat.site)
    }

    // Review Focus 3: offline first run, no forecast ever fetched.
    @Test
    fun noForecastEverFetchedIsMissingAndQuiet() {
        val heat = reason(null)
        assertEquals(date, heat.date)
        assertEquals(Freshness.MISSING, heat.freshness)
        assertEquals(HeatLevel.NORMAL, heat.dayLevel)
        assertTrue(heat.hours.isEmpty())
        assertNull(heat.peak)
        assertNull(heat.now)
        assertFalse(heat.bumped)
        assertEquals(CrewRuleLine(35.0, null, 0, null), heat.crewRule)
        assertNull(heat.heavyJobsBefore)
        assertNull(heat.uvPeak)
        assertNull(heat.sunProtection)
        assertNull(heat.suggestedCadence)
        assertNull(heat.updatedAtMillis)
        assertNull(heat.refreshedBy)
        assertNull(heat.error)
        // A failed first fetch keeps its error for the Retry card.
        val failed = reason(ForecastSnapshot("Birrarung Marr", 0L, emptyList(), error = "No connection", outcome = "failed"))
        assertEquals(Freshness.MISSING, failed.freshness)
        assertEquals("No connection", failed.error)
        assertNull(failed.updatedAtMillis)
        assertNull(failed.refreshedBy)
    }

    @Test
    fun shiftEndingAtOrBeforeItsStartHasNoHours() {
        val forecast = fresh(hotDay())
        assertTrue(reason(forecast, profile.copy(shiftStartMinute = 900, shiftEndMinute = 900)).hours.isEmpty())
        // Same hour, finish before start: the floored hour must not sneak in.
        assertTrue(reason(forecast, profile.copy(shiftStartMinute = 450, shiftEndMinute = 435)).hours.isEmpty())
        assertEquals(HeatLevel.NORMAL, reason(forecast, profile.copy(shiftStartMinute = 900, shiftEndMinute = 420)).dayLevel)
    }

    @Test
    fun suppliedClockMakesResultsRepeatableAndChangesTheForecastDateAtMidnight() {
        val combined = snapshot(hotDay() + hotDay(date.plusDays(1)), millisAt(date.atTime(23, 0)))
        val before = date.atTime(23, 59)
        val first = reason(combined, at = before)
        assertEquals(first, reason(combined, at = before))
        assertEquals(date, first.date)
        val after = reason(combined, at = date.plusDays(1).atTime(0, 0))
        assertEquals(date.plusDays(1), after.date)
        assertTrue(after.hours.all { it.time.toLocalDate() == after.date })
        assertEquals(millisAt(date.atTime(23, 0)), after.updatedAtMillis)
    }

    private fun clockAt(time: LocalDateTime): Clock = Clock.fixed(time.atZone(MelbourneZone).toInstant(), MelbourneZone)
    private fun millisAt(time: LocalDateTime): Long = time.atZone(MelbourneZone).toInstant().toEpochMilli()

    /** Dry, windy hour by default: feels-like stays below air, so the heat number equals air temperature. */
    private fun hour(at: LocalDateTime, airC: Double, uv: Double = 0.0, uvClearSky: Double? = null,
                     humidity: Double = 10.0, wind: Double = 8.0) =
        ForecastHour(time = at.toString(), temperature = airC, humidity = humidity, uv = uv, windSpeed = wind, uvClearSky = uvClearSky)

    private fun hotDay(day: LocalDate = date) = (0..23).map { h -> hour(day.atTime(h, 0), dayAir[h] ?: 22.0) }
    private fun shiftOf(airC: (Int) -> Double) = (7..14).map { h -> hour(date.atTime(h, 0), airC(h)) }
    private fun snapshot(hours: List<ForecastHour>, fetchedAt: Long) =
        ForecastSnapshot(site = "Birrarung Marr", fetchedAt = fetchedAt, hours = hours)
    private fun fresh(hours: List<ForecastHour>) = snapshot(hours, millisAt(morning) - 10 * 60_000L)
    private fun reason(forecast: ForecastSnapshot?, profile: HeatProfile = this.profile, at: LocalDateTime = morning) =
        ContextReasoner.todayHeat(forecast, profile, clockAt(at))

    /** Air in the 4 Oct payload by label hour: 15° before the 05:00 label, then 20° plus half a degree an hour. */
    private fun dstLabelAir(labelHour: Int): Double = if (labelHour < 5) 15.0 else 20.0 + labelHour * 0.5

    /**
     * 4–5 Oct 2026 as Open-Meteo returns them to a request made before 02:00 on 4 Oct.
     * - Every label is at +10, so 4 Oct still has a 02:00 label, and each later label is an hour behind the clock.
     * - The hours go through the app's own merge, which turns them into Melbourne wall-clock time.
     * - Humidity is 10 % and wind 8 m/s, the same dry, windy air as [hour]. UV is 5 for the labels 08:00–14:00.
     */
    private fun dstStartHours(): List<ForecastHour> {
        val labels = (0 until 48).map { LocalDate.of(2026, 10, 4).atStartOfDay().plusHours(it.toLong()) }
        val times = labels.map { it.toString() }
        val weather = ForecastResponse("Australia/Melbourne", WeatherHours(times, labels.map { dstLabelAir(it.hour) },
            labels.map { 10.0 }, null, wind = labels.map { 8.0 }), utcOffsetSeconds = 36_000)
        val uv = ForecastResponse("Australia/Melbourne", WeatherHours(times, null, null,
            labels.map { if (it.hour in 8..14) 5.0 else 0.0 }), utcOffsetSeconds = 36_000)
        return WeatherRepository.merge(weather, uv)
    }
}
