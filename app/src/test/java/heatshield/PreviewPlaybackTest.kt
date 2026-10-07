package heatshield

import heatshield.data.IndexedReading
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplaySimulator
import heatshield.ui.previewReadings
import heatshield.ui.virtualTimeOf
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Preview stays on the labelled sensor day, and its clock runs 45 times faster than real time. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewPlaybackTest {
    private val day = SensorReplaySimulator.PREVIEW_DATE

    @Test
    fun afterTheDaysLastReadingPlaybackStartsAgainAtNineFiftyTwo() = runTest {
        val simulator = SensorReplaySimulator({ dayFixture() }, nowMillis = { testScheduler.currentTime })
        val played = previewReadings(simulator, from = null).take(5).toList()
        assertEquals(listOf(1, 2, 1, 2, 1), played.map { it.index })
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L), played.map { it.emittedAtMillis })
    }

    @Test
    fun nextReadingPastTheEndOfTheDayGoesBackToTheStart() = runTest {
        val simulator = SensorReplaySimulator({ dayFixture() }, nowMillis = { testScheduler.currentTime })
        val played = previewReadings(simulator, from = 3).take(2).toList()
        assertEquals(listOf(1, 2), played.map { it.index })
        assertEquals(listOf(0L, 20_000L), played.map { it.emittedAtMillis })
    }

    @Test
    fun aFileWithoutThePreviewDayIsUnavailableRatherThanMislabelled() = runTest {
        val otherDay = listOf(day.minusDays(1).atTime(10, 0), day.minusDays(1).atTime(10, 15)).map { reading(it) }
        val simulator = SensorReplaySimulator({ otherDay }, nowMillis = { testScheduler.currentTime })
        var rejected = false
        try { previewReadings(simulator, from = null).take(1).toList() }
        catch (_: IllegalArgumentException) { rejected = true }
        org.junit.Assert.assertTrue("Another day cannot use the 9 January banner", rejected)
    }

    @Test
    fun virtualClockRunsFortyFiveTimesFasterAndHoldsAtTheNextReading() {
        val played = IndexedReading(1, dayFixture()[1], emittedAtMillis = 1_000_000L)
        val tenAm = day.atTime(10, 0)
        assertEquals(tenAm, virtualTimeOf(played, 1_000_000L))
        assertEquals(tenAm.plusSeconds(45), virtualTimeOf(played, 1_001_000L))
        assertEquals(tenAm.plusMinutes(15), virtualTimeOf(played, 1_020_000L))
        assertEquals(tenAm.plusMinutes(15), virtualTimeOf(played, 1_090_000L))
        assertEquals(tenAm, virtualTimeOf(played, 999_000L))
    }

    @Test
    fun lastReadingNeverAdvancesIntoTheNextLabelledDay() {
        val observed = day.atTime(23, 53)
        val played = IndexedReading(2, reading(observed), emittedAtMillis = 1_000_000L)
        assertEquals(observed, virtualTimeOf(played, 999_000L))
        assertEquals(observed.plusSeconds(45), virtualTimeOf(played, 1_001_000L))
        assertEquals(day.atTime(java.time.LocalTime.MAX), virtualTimeOf(played, 1_020_000L))
        assertEquals(day.atTime(java.time.LocalTime.MAX), virtualTimeOf(played, 1_090_000L))
    }

    private fun dayFixture() = listOf(day.atTime(9, 45), day.atTime(10, 0), day.atTime(10, 15), day.plusDays(1).atTime(0, 0))
        .map { reading(it) }

    private fun reading(local: LocalDateTime): SensorReading {
        val observed = local.atZone(MelbourneZone).toOffsetDateTime()
        return SensorReading("COM-microclimate-sensors-data", "ICTMicroclimate-01",
            observed.withOffsetSameInstant(ZoneOffset.UTC).toString(), observed.toString(), 30.0, 40.0,
            -37.8185931, 144.9716404, "Birrarung Marr Park - Pole 1131", "observed_environmental_sensor_replay")
    }
}
