package heatshield

import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.MelbourneZone
import heatshield.data.SensorReplaySimulator
import heatshield.data.ShiftPlan
import heatshield.data.WorkContext
import heatshield.domain.ContextReasoner
import java.io.File
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision inputs and boundaries, independent of screens, persistence and network availability. */
class DecisionRulesTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val now = date.atTime(6, 0).atZone(MelbourneZone).toInstant().toEpochMilli()
    private val context = WorkContext(dayMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

    @Test
    fun invalidWorkContextBlocksComparison() {
        listOf(context.copy(work = " "), context.copy(work = "x".repeat(101)), context.copy(effort = "Unknown"),
            context.copy(clothing = "Unknown"), context.copy(startHour = -1), context.copy(startHour = 23),
            context.copy(startMinute = 60), context.copy(endMinute = 470), context.copy(endMinute = 1440),
            context.copy(site = "")).forEach { invalid ->
            assertFalse("Invalid context: $invalid", invalid.isValid)
            assertTrue(ContextReasoner.compare(invalid, forecast(), now).isEmpty())
        }
        assertTrue(context.copy(startHour = 22, startMinute = 59, endMinute = 1439).isValid)
    }

    // Forecast decisions: cache age, complete two-hour intervals and ordering.
    @Test
    fun forecastExpiresAfterOneHourWithoutExtendingAgeAfterFailedRefresh() {
        val snapshot = forecast()
        assertTrue(snapshot.isFresh(now + ForecastSnapshot.MAX_AGE_MILLIS - 1))
        assertFalse(snapshot.isFresh(now + ForecastSnapshot.MAX_AGE_MILLIS))
        assertFalse(snapshot.isFresh(now - 1))
        assertFalse(snapshot.copy(fetchedAt = 0).isFresh(now))
        assertFalse(snapshot.copy(hours = emptyList()).isFresh(now))
        val failedRefresh = snapshot.copy(error = "Offline", outcome = "failed", lastAttempt = now + 1_000)
        assertTrue(failedRefresh.isFresh(now))
        assertEquals(snapshot.fetchedAt, failedRefresh.lastSuccess)
        assertFalse(failedRefresh.isFresh(now + ForecastSnapshot.MAX_AGE_MILLIS))
    }

    @Test
    fun comparisonsRespectDateShiftStartAndEndAndRankActualForecast() {
        val windows = ContextReasoner.compare(context.copy(startHour = 8, startMinute = 30), forecast(), now)
        assertEquals(listOf(9, 10, 11, 12, 13), windows.map { it.startHour })
        assertTrue(windows.all { it.endHour <= 15 })
        assertEquals("09:00–11:00", windows.first().label)
        assertNotEquals(windows.first().score, windows.last().score)
        val tomorrow = context.copy(dayMillis = context.dayMillis + 86_400_000L)
        assertTrue(ContextReasoner.compare(tomorrow, forecast(), now).isEmpty())
    }

    @Test
    fun comparisonsEndByTheUsualFinishForEarlyAndLateShifts() {
        val early = context.copy(startHour = 6, endMinute = 13 * 60)
        val wide = ForecastSnapshot(context.site, now, (5..16).map { hour ->
            ForecastHour(date.atTime(hour, 0).toString(), 22.0 + hour, 55.0, 5.0)
        })
        val windows = ContextReasoner.compare(early, wide, now)
        assertEquals((6..11).toList(), windows.map { it.startHour })
        assertEquals("06:00–08:00", windows.first().label)
        assertEquals("11:00–13:00", windows.last().label)
        assertTrue(windows.all { it.endHour * 60 <= early.endMinute })

        val midnight = date.atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
        val allDay = ForecastSnapshot(context.site, midnight, (0..23).map { hour ->
            ForecastHour(date.atTime(hour, 0).toString(), 20.0, 55.0, 5.0)
        })
        val late = context.copy(startHour = 14, endMinute = 22 * 60)
        assertEquals((14..20).toList(), ContextReasoner.compare(late, allDay, midnight).map { it.startHour })
        val wholeDay = context.copy(startHour = 0, endMinute = 1439)
        assertEquals((0..21).toList(), ContextReasoner.compare(wholeDay, allDay, midnight).map { it.startHour })
        assertEquals(13 * 60, ShiftPlan(ownerId = "worker-a", endMinute = 13 * 60).context.endMinute)
    }

    @Test
    fun comparisonsSkipWindowsThatCrossADaylightSavingChange() {
        // A whole-day shift with an even forecast for every wall-clock label 00:00–23:00, read at midnight.
        fun windowsOn(day: LocalDate): List<Int> {
            val midnight = day.atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
            val allDay = ForecastSnapshot(context.site, midnight, (0..23).map { hour ->
                ForecastHour(day.atTime(hour, 0).toString(), 20.0, 55.0, 5.0)
            })
            val wholeDay = context.copy(dayMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                startHour = 0, endMinute = 1439)
            return ContextReasoner.compare(wholeDay, allDay, midnight).map { it.startHour }
        }
        // 4 Oct 2026: Melbourne clocks jump from 02:00 to 03:00. 00:00–02:00 ends in the skipped hour, 01:00–03:00
        // lasts one real hour and 02:00 never happens, so the first window is 03:00–05:00. This payload even labels
        // the missing 02:00 (Task 4's merge never does), so only the daylight-saving check can drop those windows.
        assertEquals((3..21).toList(), windowsOn(LocalDate.of(2026, 10, 4)))
        // 4 Apr 2027: clocks fall back from 03:00 to 02:00, so 01:00–03:00 and 02:00–04:00 each last three real
        // hours. 00:00–02:00 ends before the change and stays.
        assertEquals(listOf(0) + (3..21), windowsOn(LocalDate.of(2027, 4, 4)))
        // An ordinary day keeps every window.
        assertEquals((0..21).toList(), windowsOn(date))
    }

    @Test
    fun comparisonsRequireBothBoundariesCurrentDataAndMatchingSite() {
        val base = forecast()
        assertTrue(ContextReasoner.compare(context, null, now).isEmpty())
        assertTrue(ContextReasoner.compare(context, base.copy(site = "Carlton Gardens"), now).isEmpty())
        assertTrue(ContextReasoner.compare(context, base, now + ForecastSnapshot.MAX_AGE_MILLIS).isEmpty())
        val gap = base.copy(hours = base.hours.filter { it.localTime?.hour != 10 })
        assertTrue(ContextReasoner.compare(context, gap, now).none { it.startHour in 8..10 })
        val missingEnd = base.copy(hours = base.hours.filter { it.localTime?.hour != 15 })
        assertTrue(ContextReasoner.compare(context, missingEnd, now).none { it.startHour == 13 })
        val atLastStart = date.atTime(13, 0).atZone(MelbourneZone).toInstant().toEpochMilli()
        assertEquals(listOf(13), ContextReasoner.compare(context, base.copy(fetchedAt = atLastStart), atLastStart).map { it.startHour })
        val afterLastStart = atLastStart + 1
        assertTrue(ContextReasoner.compare(context, base.copy(fetchedAt = afterLastStart), afterLastStart).isEmpty())
    }

    @Test
    fun invalidWeatherOrDuplicateHoursBlockComparisons() {
        val base = forecast()
        assertTrue(ContextReasoner.compare(context, base.copy(hours = base.hours + base.hours.first()), now).isEmpty())
        val first = base.hours.first()
        listOf(first.copy(uv = Double.NaN), first.copy(uv = -0.1), first.copy(uv = 30.1),
            first.copy(temperature = 65.1), first.copy(humidity = -0.1),
            first.copy(time = "invalid"), first.copy(time = "${date}T07:30")).forEach { invalid ->
            assertTrue("Malformed forecast must block comparison: $invalid",
                ContextReasoner.compare(context, base.copy(hours = listOf(invalid) + base.hours.drop(1)), now).isEmpty())
        }
    }

    @Test
    fun comparisonsRankLowerLoadBeforeEarlierTimesAndBreakTiesChronologically() {
        val base = forecast()
        val cooling = base.copy(hours = base.hours.map { it.copy(temperature = 45.0 - it.localTime!!.hour) })
        val ranked = ContextReasoner.compare(context, cooling, now)
        assertEquals((13 downTo 7).toList(), ranked.map { it.startHour })
        assertTrue(ranked.zipWithNext().all { (first, second) -> first.score < second.score })
        val stable = base.copy(hours = base.hours.map { it.copy(temperature = 25.0) }.reversed())
        assertEquals((7..13).toList(), ContextReasoner.compare(context, stable, now).map { it.startHour })
    }

    // Historical sensor provenance remains protected after its live reasoning is removed.
    @Test
    fun bundledReplayContainsAllHistoricalReadingsWithoutCurrentEmissionTime() {
        val readings = SensorReplaySimulator.parse(StringReader(replayCsv()))
        assertEquals(288, readings.size)
        assertTrue(readings.all { it.isValid })
        assertTrue(readings.all { it.emittedAt == 0L })
        assertTrue(readings.first().observedAtLocal.startsWith("2026-01-07"))
        assertTrue(readings.last().observedAtLocal.startsWith("2026-01-09"))
        assertFalse(readings.first().copy(provenance = "live_sensor").isValid)
    }

    @Test
    fun replayRejectsCorruptValuesAndMisrepresentedProvenanceWithoutSkippingRows() {
        val lines = replayCsv().trim().lines()
        val changes = listOf(0 to "different-source", 1 to "different-device", 2 to "invalid-timestamp",
            3 to "2026-01-07T12:00:00+11:00", 4 to "NaN", 5 to "101", 6 to "0", 7 to "0",
            8 to "Another location", 9 to "live_sensor")
        changes.forEach { (column, value) ->
            val cells = lines[1].split(',').toMutableList().apply { this[column] = value }
            val modified = lines.toMutableList().apply { this[1] = cells.joinToString(",") }
            assertThrows("Invalid replay column $column", IllegalArgumentException::class.java) {
                SensorReplaySimulator.parse(StringReader(modified.joinToString("\n")))
            }
        }
    }

    @Test
    fun replayRejectsWrongSchemaMissingRowsDuplicatesAndReversedObservations() {
        val lines = replayCsv().trim().lines()
        val invalidFiles = listOf(
            "schema" to lines.toMutableList().apply { this[0] = "unexpected_header" },
            "missing column" to lines.toMutableList().apply { this[1] = this[1].substringBeforeLast(',') },
            "missing observation" to lines.dropLast(1),
            "duplicate observation" to lines.toMutableList().apply { this[2] = this[1] },
            "reversed observations" to (lines.take(1) + lines.drop(1).reversed())
        )
        invalidFiles.forEach { (label, modified) ->
            assertThrows(label, IllegalArgumentException::class.java) { SensorReplaySimulator.parse(StringReader(modified.joinToString("\n"))) }
        }
    }

    @Test
    fun windowSummariesUseTheSameRoundedTemperatureAndUvCategoryAsToday() {
        val rounded = forecast().copy(hours = forecast().hours.map { it.copy(temperature = 31.5, humidity = 54.6, uv = 2.5) })
        val window = ContextReasoner.compare(context, rounded, now).first()
        assertEquals(32, window.maxAirC)
        assertEquals(55, window.maxHumidityPct)
        assertEquals(heatshield.domain.UvCategory.MODERATE, window.uvCategory)
    }

    private fun forecast() = ForecastSnapshot(context.site, now, (7..15).map { hour ->
        ForecastHour(date.atTime(hour, 0).toString(), 22.0 + hour, 55.0, 5.0)
    })

    private fun replayCsv() = File("src/main/assets/microclimate_replay.csv").readText()
}
