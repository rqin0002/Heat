package heatshield

import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.HeatRules
import heatshield.domain.PersonalFactors
import java.io.File
import java.io.StringReader
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 9 Jan 2026 sensor day goes through the same reasoner as Today, with still air assumed. */
class PreviewHeatTest {
    private val rows = SensorReplaySimulator.parse(StringReader(File("src/main/assets/microclimate_replay.csv").readText()))
    private val profile = HeatProfile("Birrarung Marr", "Pruning & hedging", 420, 900, 35.0, PersonalFactors())

    private fun at(localPrefix: String) =
        rows.indexOfFirst { it.observedAtLocal.startsWith(localPrefix) }.also { require(it >= 0) { localPrefix } }

    /** A made-up 9 Jan reading at [hour]:[minute], for a case the bundled file does not show on its own. */
    private fun reading(hour: Int, minute: Int, airC: Double, humidityPct: Double): SensorReading {
        val observed = SensorReplaySimulator.PREVIEW_DATE.atTime(hour, minute).atZone(MelbourneZone).toOffsetDateTime()
        return SensorReading("COM-microclimate-sensors-data", "ICTMicroclimate-01",
            observed.withOffsetSameInstant(ZoneOffset.UTC).toString(), observed.toString(), airC, humidityPct,
            -37.8185931, 144.9716404, "Birrarung Marr Park - Pole 1131", "observed_environmental_sensor_replay")
    }

    @Test
    fun dayHoursKeepTheHighestHeatNumberOfEachShiftHourWithStillAir() {
        val heat = ContextReasoner.previewHeat(rows, at("2026-01-09T09:52"), profile)
        assertEquals((7..14).toList(), heat.dayHours.map { it.time.hour })
        assertTrue(heat.dayHours.all { it.time.toLocalDate() == SensorReplaySimulator.PREVIEW_DATE && it.time.minute == 0 })
        assertTrue(heat.dayHours.all { it.windMs == 0.0 })
        // 10:52 has the highest heat number of the 10:00 hour: 35.6° air at 24.2 %, heat number 36.2.
        val ten = heat.dayHours.single { it.time.hour == 10 }
        assertEquals(35.599998, ten.airC, 1e-9)
        assertEquals(24.200001, ten.humidityPct, 1e-9)
        // 14:53 has the shift's highest heat number: 42.9° air at 7.4 %, feels like 41.0, so the heat number is the air,
        // shown 43°: Extreme.
        val peak = heat.peak!!
        assertEquals(14, peak.time.hour)
        assertEquals(42.900002, peak.airC, 1e-9)
        assertEquals(HeatLevel.EXTREME, peak.effective)
    }

    @Test
    fun anHourKeepsItsHighestHeatNumberEvenWhenAnotherReadingHasHotterAir() {
        // Real readings. In both evening hours the hottest air and the highest heat number come from different
        // readings, and the shown number differs:
        // 21:00 is 26.4° at 35.8 %, heat number 26.45 (26°); 21:38 is 26.3° at 37.4 %, 26.51 (27°). The hour keeps 21:38.
        // 22:08 is 26.1° at 38.3 %, heat number 26.36 (26°); 22:23 is 25.8° at 43.6 %, 26.56 (27°). The hour keeps 22:23.
        val evening = profile.copy(shiftStartMinute = 21 * 60, shiftEndMinute = 23 * 60)
        val hours = ContextReasoner.previewHeat(rows, 0, evening).dayHours
        assertEquals(listOf(21, 22), hours.map { it.time.hour })
        assertEquals(26.299999, hours[0].airC, 1e-9)
        assertEquals(37.400002, hours[0].humidityPct, 1e-9)
        assertEquals(25.799999, hours[1].airC, 1e-9)
        assertEquals(43.599998, hours[1].humidityPct, 1e-9)
        assertEquals(listOf(27, 27), hours.map { HeatRules.wholeDegrees(it.heat) })
    }

    @Test
    fun theHourHatchChecksEveryReadingsAirNotOnlyTheReadingShown() {
        // A made-up 11:00 hour under a 35° rule. 11:07 is hot and dry: 35.0° at 10 %, feels like 32.8, so its heat
        // number is the air, 35.0. 11:22 is cooler and humid: 33.0° at 70 %, heat number 40.58 (41°, Extreme). The hour
        // shows 11:22, the higher heat number, and is still hatched, because 11:07's air reached the rule.
        val fixture = listOf(reading(11, 7, 35.0, 10.0), reading(11, 22, 33.0, 70.0))
        val heat = ContextReasoner.previewHeat(fixture, 1, profile)
        val eleven = heat.dayHours.single()
        assertEquals(11, eleven.time.hour)
        assertEquals(33.0, eleven.airC, 0.0)
        assertEquals(70.0, eleven.humidityPct, 0.0)
        assertEquals(41, HeatRules.wholeDegrees(eleven.heat))
        assertEquals(HeatLevel.EXTREME, eleven.effective)
        assertTrue(eleven.overTrigger)
        // The crew line counts the same 11:07 reading.
        assertEquals(LocalTime.of(11, 7), heat.crewRule.firstHour)
        assertEquals(1, heat.crewRule.hoursInShift)
        assertEquals(35.0, heat.crewRule.peakAirC!!, 0.0)
        // Under a 36° rule neither reading's air shows 36° or more, so the hour is not hatched.
        assertFalse(ContextReasoner.previewHeat(fixture, 1, profile.copy(triggerC = 36.0)).dayHours.single().overTrigger)
    }

    @Test
    fun shiftHoursFollowTheProfileIncludingAPartFirstHour() {
        val late = profile.copy(shiftStartMinute = 9 * 60 + 30, shiftEndMinute = 12 * 60 + 30)
        assertEquals(listOf(9, 10, 11, 12), ContextReasoner.previewHeat(rows, 0, late).dayHours.map { it.time.hour })
        // The sensor day has a reading in every hour, so Preview's ribbon never has a missing hour.
        val allDay = profile.copy(shiftStartMinute = 0, shiftEndMinute = 24 * 60)
        assertEquals((0..23).toList(), ContextReasoner.previewHeat(rows, 0, allDay).dayHours.map { it.time.hour })
    }

    @Test
    fun currentIsTheReadingAtTheIndexThroughTheSameRules() {
        // 10:52 is 35.6° air at 24.2 %: heat number 36.2, shown 36°, so Very hot; heavy work adds one level, to Extreme.
        val index = at("2026-01-09T10:52")
        val heat = ContextReasoner.previewHeat(rows, index, profile)
        assertSame(rows[index], heat.reading)
        assertEquals(35.599998, heat.current.airC, 1e-9)
        assertEquals(0.0, heat.current.windMs, 0.0)
        assertEquals(LocalTime.of(10, 52, 55), heat.current.time.toLocalTime())
        assertEquals(HeatLevel.VERY_HOT, heat.current.base)
        val heavy = ContextReasoner.previewHeat(rows, index, profile.copy(factors = PersonalFactors(heavyWork = true)))
        assertEquals(HeatLevel.EXTREME, heavy.current.effective)
    }

    @Test
    fun crewRuleFirstTimeComesFromSensorAirUpToTheCurrentReading() {
        // Sensor air in whole degrees, as shown: 10:37 is 31.2° (31°), 10:52 is 35.6° (36°), so a 35° rule is reached at 10:52.
        val before = ContextReasoner.previewHeat(rows, at("2026-01-09T10:37"), profile).crewRule
        assertNull(before.firstHour)
        assertEquals(0, before.hoursInShift)
        assertEquals(35.0, before.triggerC, 0.0)
        assertEquals(31.200001, before.peakAirC!!, 1e-9)
        val crossing = ContextReasoner.previewHeat(rows, at("2026-01-09T10:52"), profile).crewRule
        assertEquals(LocalTime.of(10, 52), crossing.firstHour)
        assertEquals(1, crossing.hoursInShift)
        assertEquals(35.599998, crossing.peakAirC!!, 1e-9)
        val endOfShift = ContextReasoner.previewHeat(rows, at("2026-01-09T14:53"), profile).crewRule
        assertEquals(LocalTime.of(10, 52), endOfShift.firstHour)
        assertEquals(5, endOfShift.hoursInShift)
        // 11:07 is 37.0° (37°), under 38. 11:22 is 37.8°, which shows "38°", so it reaches a 38° rule (whole degrees,
        // as Today judges it); the next reading, 11:37, is 39.0°. Hours 11, 12, 13 and 14 each have a reading at 38° or more.
        val strict = ContextReasoner.previewHeat(rows, at("2026-01-09T14:53"), profile.copy(triggerC = 38.0)).crewRule
        assertEquals(LocalTime.of(11, 22), strict.firstHour)
        assertEquals(4, strict.hoursInShift)
    }

    @Test
    fun crewRuleUsesAirNotTheHeatNumber() {
        // 10:37 is 31.2 °C air at 39.6 % humidity: the heat number is about 33.1 (shown 33°), the air shows 31°, under 33.
        val heat = ContextReasoner.previewHeat(rows, at("2026-01-09T10:37"), profile.copy(triggerC = 33.0))
        assertTrue(heat.current.heat >= 33.0)
        assertNull(heat.crewRule.firstHour)
    }
}
