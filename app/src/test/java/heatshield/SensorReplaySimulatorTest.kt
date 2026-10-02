package heatshield

import heatshield.data.IndexedReading
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.data.WorkSite
import java.io.File
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulator plays a historical file on a timer; virtual time keeps these tests instant. */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorReplaySimulatorTest {
    private val day = SensorReplaySimulator.PREVIEW_DATE

    @Test
    fun reorderingBuiltInParksDoesNotChangeTheHistoricalSensorsIdentity() {
        val parks = WorkSite.defaults as MutableList<WorkSite>
        val original = parks.toList()
        try {
            java.util.Collections.swap(parks, 0, 1)
            assertEquals("carlton-gardens", parks.first().id)
            val rows = SensorReplaySimulator.parse(StringReader(File("src/main/assets/microclimate_replay.csv").readText()))
            assertEquals(288, rows.size)
            assertTrue(rows.all { it.latitude == -37.8185931 && it.longitude == 144.9716404 })
        } finally {
            original.forEachIndexed { index, site -> parks[index] = site }
        }
    }

    @Test
    fun firstReadingIsImmediateAndTheNextArrivesTwentySecondsLater() = runTest {
        val simulator = SensorReplaySimulator({ fixture() }, nowMillis = { testScheduler.currentTime })
        val seen = mutableListOf<IndexedReading>()
        backgroundScope.launch { simulator.readings(1).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(1), seen.map { it.index })
        assertEquals(0L, seen.single().emittedAtMillis)
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1, 2), seen.map { it.index })
        assertEquals(20_000L, seen.last().emittedAtMillis)
    }

    @Test
    fun readingsLoopAfterTheLastRowAndStampTheirEmissionTime() = runTest {
        val simulator = SensorReplaySimulator({ fixture() }, nowMillis = { testScheduler.currentTime })
        val played = simulator.readings(2).take(5).toList()
        assertEquals(listOf(2, 3, 0, 1, 2), played.map { it.index })
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L), played.map { it.emittedAtMillis })
        assertTrue(played.all { it.reading.emittedAt == it.emittedAtMillis })
        assertTrue(simulator.rows().all { it.emittedAt == 0L })
    }

    @Test
    fun startIndexWrapsAndTheFileIsReadOnce() = runTest {
        var loads = 0
        val simulator = SensorReplaySimulator({ loads++; fixture() }, nowMillis = { testScheduler.currentTime })
        assertEquals(listOf(1, 2), simulator.readings(5).take(2).toList().map { it.index })
        assertEquals(listOf(3), simulator.readings(-1).take(1).toList().map { it.index })
        assertEquals(4, simulator.rows().size)
        assertEquals(1, loads)
    }

    @Test
    fun oneReadingEveryTwentySecondsPlaysFifteenMinuteObservationsFortyFiveTimesFaster() {
        assertEquals(20_000L, SensorReplaySimulator.INTERVAL_MILLIS)
        assertEquals(15 * 60 * 1000L, SensorReplaySimulator.INTERVAL_MILLIS * SensorReplaySimulator.VIRTUAL_SPEEDUP)
    }

    @Test
    fun previewStartsAtNineFiftyTwoOnNineJanuaryInTheBundledFile() {
        val rows = SensorReplaySimulator.parse(StringReader(File("src/main/assets/microclimate_replay.csv").readText()))
        val start = SensorReplaySimulator.startIndexFor(rows, day, SensorReplaySimulator.PREVIEW_START)
        assertEquals(231, start)
        assertEquals(LocalDateTime.of(2026, 1, 9, 9, 52, 53), SensorReplaySimulator.localDateTimeOf(rows[start]))
        assertTrue(SensorReplaySimulator.localDateTimeOf(rows[start - 1]).toLocalTime().isBefore(SensorReplaySimulator.PREVIEW_START))
        assertEquals(0, SensorReplaySimulator.startIndexFor(rows, LocalDate.of(2026, 1, 10), SensorReplaySimulator.PREVIEW_START))
        assertEquals(0, SensorReplaySimulator.startIndexFor(rows, day, LocalTime.of(23, 54)))
        assertEquals(1, SensorReplaySimulator.startIndexFor(fixture(), day, LocalTime.of(10, 0)))
        assertEquals(day.atTime(9, 45), SensorReplaySimulator.localDateTimeOf(fixture()[0]))
    }

    private fun fixture() = listOf(day.atTime(9, 45), day.atTime(10, 0), day.atTime(10, 15), day.plusDays(1).atTime(0, 0))
        .map { local ->
            val observed = local.atZone(MelbourneZone).toOffsetDateTime()
            SensorReading("COM-microclimate-sensors-data", "ICTMicroclimate-01",
                observed.withOffsetSameInstant(ZoneOffset.UTC).toString(), observed.toString(), 30.0, 40.0,
                -37.8185931, 144.9716404, "Birrarung Marr Park - Pole 1131", "observed_environmental_sensor_replay")
        }
}
