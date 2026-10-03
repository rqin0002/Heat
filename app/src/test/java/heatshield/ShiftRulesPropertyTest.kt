package heatshield

import heatshield.domain.BreakDue
import heatshield.domain.BreakRule
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.PersonalFactors
import heatshield.domain.ScheduledBreak
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.WorkClock
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §10 M2: more heat or more effort never delays a break; a smoko within 15 minutes covers a due break. Seeded, so a failure replays. */
class ShiftRulesPropertyTest {
    private val date: LocalDate = LocalDate.of(2026, 1, 15)
    private val start: LocalDateTime = date.atTime(7, 0)
    private val end: LocalDateTime = date.atTime(15, 0)
    private val random = Random(20261007)

    private fun day(airs: List<Double>, factors: PersonalFactors): List<HourHeat> = airs.mapIndexed { i, a ->
        val base = HeatRules.baseLevel(a)
        HourHeat(date.atTime(7 + i, 0), a, 10.0, 8.0, a - 3.0, a, base, HeatRules.effectiveLevel(base, factors), HeatRules.wholeDegrees(a) >= 35, 5.0, 5.0)
    }
    private fun randomAirs() = List(8) { 22.0 + random.nextDouble() * 20.0 }   // 22°–42°, Normal to Extreme
    private fun i(time: LocalDateTime) = ShiftRules.instant(time)
    private fun minutesOrNever(due: BreakDue): Long = due.dueAt?.let { java.time.Duration.between(i(start), it).toMinutes() } ?: Long.MAX_VALUE

    @Test
    fun raisingAnyHourNeverDelaysTheBreakUnderEitherRule() {
        repeat(400) {
            val airs = randomAirs()
            val hotter = airs.toMutableList().also { it[random.nextInt(8)] += random.nextDouble() * 8.0 }
            val factors = PersonalFactors(heavyWork = random.nextBoolean(), heavyClothing = random.nextBoolean())
            val sinceMinute = random.nextInt(0, 180)
            val clock = WorkClock(i(start.plusMinutes(sinceMinute.toLong())), emptyList(), 0)
            BreakRule.entries.forEach { rule ->
                val before = ShiftRules.nextBreakDue(clock, day(airs, factors), rule, emptyList(), null, i(end))
                val after = ShiftRules.nextBreakDue(clock, day(hotter, factors), rule, emptyList(), null, i(end))
                assertTrue("$rule airs=$airs hotter=$hotter since=$sinceMinute: $before then $after", minutesOrNever(after) <= minutesOrNever(before))
            }
        }
    }

    @Test
    fun heavyWorkNeverDelaysTheBreak() {
        repeat(400) {
            val airs = randomAirs()
            val clock = WorkClock(i(start.plusMinutes(random.nextInt(0, 180).toLong())), emptyList(), 0)
            val light = ShiftRules.nextBreakDue(clock, day(airs, PersonalFactors()), BreakRule.HEATSHIELD, emptyList(), null, i(end))
            val heavy = ShiftRules.nextBreakDue(clock, day(airs, PersonalFactors(heavyWork = true)), BreakRule.HEATSHIELD, emptyList(), null, i(end))
            assertTrue("airs=$airs: $light then $heavy", minutesOrNever(heavy) <= minutesOrNever(light))
        }
    }

    // Tracked instants carry seconds. Whatever they are, the minutes stored per level add up to the minutes worked (the
    // hours cover the shift), and no stretch without a break is longer than the shift's work (LogRules relies on both).
    @Test
    fun levelMinutesAddUpToTheWorkedMinutesWhateverTheSeconds() {
        repeat(400) {
            fun seconds() = random.nextLong(0, 60)
            val from = start.plusMinutes(random.nextLong(0, 120)).plusSeconds(seconds())
            val to = from.plusMinutes(random.nextLong(1, 600)).plusSeconds(seconds())
            val breaks = List(random.nextInt(0, 4)) {
                val breakStart = from.plusMinutes(random.nextLong(-30, 600)).plusSeconds(seconds())
                heatshield.data.ShiftEvent(shiftId = 1, type = heatshield.data.ShiftEventType.BREAK, startedAt = heatshield.domain.ShiftRules.epochMillis(breakStart),
                    endedAt = heatshield.domain.ShiftRules.epochMillis(breakStart.plusMinutes(random.nextLong(0, 45)).plusSeconds(seconds())))
            }
            val cover = (0..23).map { h -> date.atTime(h, 0) } + (0..23).map { h -> date.plusDays(1).atTime(h, 0) }
            val hours = cover.map { time ->
                val air = 22.0 + random.nextDouble() * 20.0
                val base = HeatRules.baseLevel(air)
                HourHeat(time, air, 10.0, 8.0, air - 3.0, air, base, base, false, 5.0, 5.0)
            }
            val totals = ShiftRules.summarise(i(from), i(to), breaks, hours)
            val case = "from=$from to=$to breaks=${breaks.map { ShiftRules.localTime(it.startedAt) to it.endedAt?.let(ShiftRules::localTime) }}: $totals"
            assertEquals(case, totals.outdoorMinutes, totals.minutesByLevel.values.sum())
            assertTrue(case, totals.longestStretchMinutes <= totals.outdoorMinutes)
            assertTrue(case, totals.outdoorMinutes + totals.breakMinutes <= java.time.Duration.between(from, to).toMinutes() + 1)
        }
    }

    @Test
    fun aPendingSmokoWithinFifteenMinutesOfTheDueTimeAlwaysCoversIt() {
        repeat(400) {
            val airs = randomAirs()
            val clock = WorkClock(i(start.plusMinutes(random.nextInt(0, 240).toLong())), emptyList(), 0)
            val plain = ShiftRules.nextBreakDue(clock, day(airs, PersonalFactors()), BreakRule.HEATSHIELD, emptyList(), null, i(end)) as? BreakDue.At ?: return@repeat
            val smokoAt = plain.time.plus(java.time.Duration.ofMinutes(random.nextInt(0, 15).toLong()))
            val smoko = ScheduledBreak(ScheduledKind.SMOKO, smokoAt)
            val covered = ShiftRules.nextBreakDue(clock, day(airs, PersonalFactors()), BreakRule.HEATSHIELD, listOf(smoko), null, i(end))
            // The minutes the smoko defers the break over, the smoko's own minute included, are walked: the first at the
            // stop line is the stop, and an unknown hour cannot be timed through (Astra M2 #1, fix round 1 M4).
            val deferred = generateSequence(plain.time) { it.plus(java.time.Duration.ofMinutes(1)) }.takeWhile { !it.isAfter(smokoAt) }
                .map { it to ShiftRules.hourAt(day(airs, PersonalFactors()), it) }
                .firstOrNull { (_, hour) -> hour == null || ShiftRules.stopNow(hour, BreakRule.HEATSHIELD) }
            when {
                smokoAt == plain.time -> assertEquals(plain, covered)   // due exactly at smoko time: the guard is [S−15, S)
                deferred?.second == null && deferred != null -> assertEquals(BreakDue.NoForecast, covered)
                deferred != null -> assertEquals(BreakDue.Stop(deferred.first, BreakRule.HEATSHIELD), covered)
                else -> assertEquals(BreakDue.At(smokoAt, plain.level, plain.cadence, scheduled = ScheduledKind.SMOKO), covered)
            }
            val farSmoko = ScheduledBreak(ScheduledKind.SMOKO, plain.time.plus(java.time.Duration.ofMinutes(16)))
            assertEquals(plain, ShiftRules.nextBreakDue(clock, day(airs, PersonalFactors()), BreakRule.HEATSHIELD, listOf(farSmoko), null, i(end)))
        }
    }
}
