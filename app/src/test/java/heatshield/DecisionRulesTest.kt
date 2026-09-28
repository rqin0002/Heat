package heatshield

import heatshield.background.reminderEligible
import heatshield.data.ForecastHour
import heatshield.data.ForecastResponse
import heatshield.data.ForecastSnapshot
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplay
import heatshield.data.ShiftPlan
import heatshield.data.WeatherHours
import heatshield.data.WeatherRepository
import heatshield.data.WorkContext
import heatshield.domain.ContextEngine
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
    private val reading = SensorReading("COM-microclimate-sensors-data", "ICTMicroclimate-01",
        "2026-01-07T00:00:00+00:00", "2026-01-07T11:00:00+11:00", 28.0, 50.0,
        -37.8185931, 144.9716404, "Birrarung Marr Park - Pole 1131", "observed_environmental_sensor_replay",
        emittedAt = now)
    private val times = (8..11).map { date.atTime(it, 0).toString() }
    private val weather = ForecastResponse("Australia/Melbourne", WeatherHours(times,
        listOf(24.0, 25.0, 26.0, 27.0), listOf(50.0, 51.0, 52.0, 53.0), null))
    private val ultraviolet = ForecastResponse("Australia/Melbourne", WeatherHours(times, null, null,
        listOf(4.0, 5.0, 6.0, 7.0)))
    private val due = 1_800_000_000_000L
    private val plan = ShiftPlan(ownerId = "worker-a", reminderAt = due, revision = 9)

    // Replay decisions: elapsed work, user context, source validity and replay timing.
    @Test
    fun effortClothingHumidityAndElapsedWorkChangeTheDecision() {
        val light = context.copy(effort = "Light effort")
        assertFalse(ContextEngine.evaluate(reading, light, 45, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading, context, 45, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(reading, context, 44, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(reading, context, 10, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading, context.copy(clothing = "Heavy protective clothing"), 10, "", now).checkInDue)

        val moderate = context.copy(effort = "Moderate effort")
        assertFalse(ContextEngine.evaluate(reading, moderate, 89, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading, moderate, 90, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(reading.copy(humidity = 69.9), context, 1, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading.copy(humidity = 70.0), context, 1, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(reading.copy(temperature = 32.0), context, 0, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading.copy(temperature = 32.0), context, 1, "", now).checkInDue)
    }

    @Test
    fun missingInvalidOrWrongSiteReadingsCannotProduceARecommendation() {
        val invalidReadings = listOf(null, reading.copy(humidity = Double.NaN),
            reading.copy(temperature = 200.0), reading.copy(humidity = 100.1),
            reading.copy(latitude = Double.POSITIVE_INFINITY), reading.copy(provenance = "live_sensor"),
            reading.copy(observedAtLocal = "invalid"), reading.copy(observedAtLocal = "2026-01-07T12:00:00+11:00"))
        invalidReadings.forEach { invalid ->
            assertFalse("Invalid reading must not recommend: $invalid",
                ContextEngine.evaluate(invalid, context, 100, "", now).checkInDue)
        }
        assertFalse(ContextEngine.evaluate(reading, context.copy(site = "Carlton Gardens"), 100, "", now).checkInDue)
    }

    @Test
    fun invalidWorkContextOrDurationBlocksEvenAnUnresolvedResponse() {
        listOf(context.copy(work = " "), context.copy(effort = "Unknown"), context.copy(clothing = "Unknown"),
            context.copy(startHour = -1), context.copy(startHour = 15), context.copy(startMinute = 60),
            context.copy(site = "")).forEach { invalid ->
            assertFalse("Invalid work context: $invalid",
                ContextEngine.evaluate(reading, invalid, 100, "Cannot pause yet", now).checkInDue)
            assertTrue(ContextEngine.compare(invalid, forecast(), now).isEmpty())
        }
        listOf(-1, 1441).forEach { minutes ->
            assertFalse(ContextEngine.evaluate(reading, context, minutes, "Cannot pause yet", now).checkInDue)
        }
    }

    @Test
    fun replayShiftIncludesItsStartAndExcludesItsEnd() {
        assertTrue(ContextEngine.evaluate(reading, context, 100, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(reading, context.copy(startHour = 12), 100, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(readingAt(7, 30), context.copy(startMinute = 30), 100, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(readingAt(7, 29), context.copy(startMinute = 30), 100, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(readingAt(14, 59), context, 100, "", now).checkInDue)
        assertFalse(ContextEngine.evaluate(readingAt(15, 0), context, 100, "", now).checkInDue)
    }

    @Test
    fun replayExpiryUsesEmissionTimeAndPreservesUnresolvedFollowUp() {
        assertTrue(ContextEngine.evaluate(reading, context, 100, "", reading.emittedAt + 39_999).checkInDue)
        assertFalse(ContextEngine.evaluate(reading, context, 100, "", reading.emittedAt + 40_000).checkInDue)
        assertFalse(ContextEngine.evaluate(reading, context, 100, "", reading.emittedAt - 1).checkInDue)
        assertFalse(ContextEngine.evaluate(reading.copy(emittedAt = 0), context, 100, "", now).checkInDue)
        assertTrue(ContextEngine.evaluate(reading, context, 100, "Cannot pause yet", reading.emittedAt + 40_000).checkInDue)
        assertTrue(ContextEngine.evaluate(null, context, 100, "No cooler place available", now).checkInDue)
        val constrained = ContextEngine.evaluate(reading, context.copy(effort = "Light effort"), 10, "Cannot pause yet", now)
        assertTrue(constrained.checkInDue)
        assertTrue(constrained.explanation.contains("Cannot pause yet"))
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
        val windows = ContextEngine.compare(context.copy(startHour = 8, startMinute = 30), forecast(), now)
        assertEquals(listOf(9, 10, 11, 12, 13), windows.map { it.startHour })
        assertTrue(windows.all { it.endHour <= 15 })
        assertEquals("09:00–11:00", windows.first().label)
        assertNotEquals(windows.first().score, windows.last().score)
        val tomorrow = context.copy(dayMillis = context.dayMillis + 86_400_000L)
        assertTrue(ContextEngine.compare(tomorrow, forecast(), now).isEmpty())
    }

    @Test
    fun comparisonsRequireBothBoundariesCurrentDataAndMatchingSite() {
        val base = forecast()
        assertTrue(ContextEngine.compare(context, null, now).isEmpty())
        assertTrue(ContextEngine.compare(context, base.copy(site = "Carlton Gardens"), now).isEmpty())
        assertTrue(ContextEngine.compare(context, base, now + ForecastSnapshot.MAX_AGE_MILLIS).isEmpty())
        val gap = base.copy(hours = base.hours.filter { it.localTime?.hour != 10 })
        assertTrue(ContextEngine.compare(context, gap, now).none { it.startHour in 8..10 })
        val missingEnd = base.copy(hours = base.hours.filter { it.localTime?.hour != 15 })
        assertTrue(ContextEngine.compare(context, missingEnd, now).none { it.startHour == 13 })
        val atLastStart = date.atTime(13, 0).atZone(MelbourneZone).toInstant().toEpochMilli()
        assertEquals(listOf(13), ContextEngine.compare(context, base.copy(fetchedAt = atLastStart), atLastStart).map { it.startHour })
        val afterLastStart = atLastStart + 1
        assertTrue(ContextEngine.compare(context, base.copy(fetchedAt = afterLastStart), afterLastStart).isEmpty())
    }

    @Test
    fun invalidWeatherOrDuplicateHoursBlockComparisons() {
        val base = forecast()
        assertTrue(ContextEngine.compare(context, base.copy(hours = base.hours + base.hours.first()), now).isEmpty())
        val first = base.hours.first()
        listOf(first.copy(uv = Double.NaN), first.copy(uv = -0.1), first.copy(uv = 30.1),
            first.copy(temperature = 65.1), first.copy(humidity = -0.1),
            first.copy(time = "invalid"), first.copy(time = "${date}T07:30")).forEach { invalid ->
            assertTrue("Malformed forecast must block comparison: $invalid",
                ContextEngine.compare(context, base.copy(hours = listOf(invalid) + base.hours.drop(1)), now).isEmpty())
        }
    }

    @Test
    fun comparisonsRankLowerLoadBeforeEarlierTimesAndBreakTiesChronologically() {
        val base = forecast()
        val cooling = base.copy(hours = base.hours.map { it.copy(temperature = 45.0 - it.localTime!!.hour) })
        val ranked = ContextEngine.compare(context, cooling, now)
        assertEquals((13 downTo 7).toList(), ranked.map { it.startHour })
        assertTrue(ranked.zipWithNext().all { (first, second) -> first.score < second.score })
        val stable = base.copy(hours = base.hours.map { it.copy(temperature = 25.0) }.reversed())
        assertEquals((7..13).toList(), ContextEngine.compare(context, stable, now).map { it.startHour })
    }

    // External data contracts: merge by timestamps and reject incomplete or misleading input.
    @Test
    fun ultravioletIsJoinedByTimestampAndMissingHoursAreNotInvented() {
        val uv = ultraviolet.copy(hourly = WeatherHours(times.reversed(), null, null, listOf(null, 6.0, 5.0, 4.0)))
        val merged = WeatherRepository.merge(weather, uv)
        assertEquals(3, merged.size)
        assertEquals(listOf(4.0, 5.0, 6.0), merged.map { it.uv })
        assertEquals(listOf(24.0, 25.0, 26.0), merged.map { it.temperature })
        assertEquals(listOf(50.0, 51.0, 52.0), merged.map { it.humidity })
        assertEquals(times.take(3), merged.map { it.time })
    }

    @Test
    fun mergeRejectsMissingFieldsMismatchedArraysAndWrongTimezones() {
        val weatherHours = weather.hourly!!
        val uvHours = ultraviolet.hourly!!
        val invalidPayloads = listOf(
            "weather timezone" to (weather.copy(timezone = "UTC") to ultraviolet),
            "UV timezone" to (weather to ultraviolet.copy(timezone = "UTC")),
            "missing weather" to (weather.copy(hourly = null) to ultraviolet),
            "missing UV" to (weather to ultraviolet.copy(hourly = null)),
            "missing times" to (weather.copy(hourly = weatherHours.copy(time = null)) to ultraviolet),
            "missing temperatures" to (weather.copy(hourly = weatherHours.copy(temperature = null)) to ultraviolet),
            "missing humidity" to (weather.copy(hourly = weatherHours.copy(humidity = null)) to ultraviolet),
            "short temperatures" to (weather.copy(hourly = weatherHours.copy(temperature = listOf(24.0))) to ultraviolet),
            "short humidity" to (weather.copy(hourly = weatherHours.copy(humidity = listOf(50.0))) to ultraviolet),
            "short UV" to (weather to ultraviolet.copy(hourly = uvHours.copy(uv = listOf(1.0))))
        )
        invalidPayloads.forEach { (label, responses) ->
            assertThrows(label, IllegalArgumentException::class.java) { WeatherRepository.merge(responses.first, responses.second) }
        }
    }

    @Test
    fun mergeRejectsDuplicateTimestampsAndInsufficientConsecutiveOverlap() {
        val weatherHours = requireNotNull(weather.hourly)
        val uvHours = requireNotNull(ultraviolet.hourly)
        val duplicatedTimes = listOf(times[0], times[0], times[2], times[3])
        val separatedTimes = listOf(8, 10, 12, 14).map { date.atTime(it, 0).toString() }
        val invalidPayloads = listOf(
            weather.copy(hourly = weatherHours.copy(time = duplicatedTimes)) to ultraviolet,
            weather to ultraviolet.copy(hourly = uvHours.copy(time = duplicatedTimes)),
            weather.copy(hourly = weatherHours.copy(time = separatedTimes)) to
                ultraviolet.copy(hourly = uvHours.copy(time = separatedTimes)),
            weather to ultraviolet.copy(hourly = WeatherHours(times.drop(2), null, null, listOf(6.0, 7.0)))
        )
        invalidPayloads.forEachIndexed { index, (conditions, uv) ->
            assertThrows("Invalid overlap case $index", IllegalArgumentException::class.java) { WeatherRepository.merge(conditions, uv) }
        }
    }

    @Test
    fun mergeOmitsInvalidPointsOnlyWhenACompleteIntervalRemains() {
        val weatherHours = requireNotNull(weather.hourly)
        val uvHours = requireNotNull(ultraviolet.hourly)
        val invalidPayloads = listOf(
            weather.copy(hourly = weatherHours.copy(temperature = listOf(24.0, 25.0, 26.0, null))) to ultraviolet,
            weather.copy(hourly = weatherHours.copy(temperature = listOf(24.0, 25.0, 26.0, Double.NaN))) to ultraviolet,
            weather.copy(hourly = weatherHours.copy(humidity = listOf(50.0, 51.0, 52.0, 100.1))) to ultraviolet,
            weather to ultraviolet.copy(hourly = uvHours.copy(uv = listOf(4.0, 5.0, 6.0, 30.1)))
        )
        invalidPayloads.forEach { (conditions, uv) ->
            val merged = WeatherRepository.merge(conditions, uv)
            assertEquals(times.take(3), merged.map { it.time })
            assertTrue(merged.all { it.isValid })
        }
    }

    @Test
    fun bundledReplayContainsAllHistoricalReadingsWithoutCurrentEmissionTime() {
        val readings = SensorReplay.parse(StringReader(replayCsv()))
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
                SensorReplay.parse(StringReader(modified.joinToString("\n")))
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
            assertThrows(label, IllegalArgumentException::class.java) { SensorReplay.parse(StringReader(modified.joinToString("\n"))) }
        }
    }

    // Reminder delivery rechecks the persisted plan, owner, preference and permitted delay.
    @Test
    fun reminderDeliversOnlyWithinTheCurrentPlansInclusiveTimeWindow() {
        assertTrue(reminderEligible(plan, "worker-a", 9, due, true, due))
        assertTrue(reminderEligible(plan, "worker-a", 9, due, true, due + 30 * 60 * 1000L))
        assertTrue(reminderEligible(plan, "worker-a", 9, due, true, due + 60 * 60 * 1000L))
    }

    @Test
    fun reminderRejectsDeletedReplacedDisabledOrOtherOwnersPlan() {
        assertFalse(reminderEligible(null, "worker-a", 9, due, true, due))
        assertFalse(reminderEligible(plan, "worker-b", 9, due, true, due))
        assertFalse(reminderEligible(plan, "worker-a", 8, due, true, due))
        assertFalse(reminderEligible(plan, "worker-a", 9, due - 1, true, due))
        assertFalse(reminderEligible(plan, "worker-a", 9, due, false, due))
        assertFalse(reminderEligible(plan.copy(reminderAt = null), "worker-a", 9, due, true, due))
    }

    @Test
    fun reminderRejectsEarlyExpiredAndUnsetDeliveryTimes() {
        assertFalse(reminderEligible(plan, "worker-a", 9, due, true, due - 1))
        assertFalse(reminderEligible(plan, "worker-a", 9, due, true, due + 60 * 60 * 1000L + 1))
        assertFalse(reminderEligible(plan.copy(reminderAt = 0), "worker-a", 9, 0, true, 0))
        assertFalse(reminderEligible(plan.copy(reminderAt = -1), "worker-a", 9, -1, true, -1))
    }

    private fun forecast() = ForecastSnapshot(context.site, now, (7..15).map { hour ->
        ForecastHour(date.atTime(hour, 0).toString(), 22.0 + hour, 55.0, 5.0)
    })

    private fun readingAt(hour: Int, minute: Int): SensorReading {
        val observed = LocalDate.of(2026, 1, 7).atTime(hour, minute).atZone(MelbourneZone).toOffsetDateTime()
        return reading.copy(observedAt = observed.withOffsetSameInstant(ZoneOffset.UTC).toString(), observedAtLocal = observed.toString())
    }

    private fun replayCsv() = File("src/main/assets/microclimate_replay.csv").readText()
}
