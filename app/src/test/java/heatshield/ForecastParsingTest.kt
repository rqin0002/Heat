package heatshield

import com.google.gson.Gson
import heatshield.data.ForecastCache
import heatshield.data.ForecastDay
import heatshield.data.ForecastHour
import heatshield.data.ForecastResponse
import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.data.ShiftSource
import heatshield.data.ShiftStatus
import heatshield.data.UserPreferences
import heatshield.data.WeatherDays
import heatshield.data.WeatherHours
import heatshield.data.WeatherRepository
import heatshield.data.WorkSession
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.OpenShift
import heatshield.domain.ShiftRules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open-Meteo payloads, the forecast cache and forecast freshness, with no network and no database. */
class ForecastParsingTest {
    private val day = LocalDate.of(2026, 10, 2)
    private val fetchedAt = day.atTime(6, 0).atZone(MelbourneZone).toInstant().toEpochMilli()
    private val snapshot = ForecastSnapshot("Birrarung Marr", fetchedAt,
        (7..15).map { hour -> ForecastHour(day.atTime(hour, 0).toString(), 20.0 + hour, 50.0, 4.0, 3.0, 5.0) })

    private fun minutes(count: Int): Long = count * 60_000L

    // Value types: wind, clear-sky UV, days, age and freshness.
    @Test
    fun hourValidityCoversWindAndClearSkyUv() {
        val hour = ForecastHour("2026-10-02T09:00", 26.0, 45.0, 3.0, 4.0, 3.5)
        assertTrue(hour.isValid)
        assertTrue(hour.copy(windSpeed = 0.0, uvClearSky = null).isValid)
        assertTrue(hour.copy(windSpeed = 80.0, uvClearSky = 30.0).isValid)
        assertEquals(0.0, ForecastHour("2026-10-02T09:00", 26.0, 45.0, 3.0).windSpeed, 0.0)
        listOf(hour.copy(windSpeed = -0.1), hour.copy(windSpeed = 80.1), hour.copy(windSpeed = Double.NaN),
            hour.copy(windSpeed = Double.POSITIVE_INFINITY), hour.copy(uvClearSky = -0.1),
            hour.copy(uvClearSky = 30.1), hour.copy(uvClearSky = Double.NaN)).forEach { invalid ->
            assertFalse("Invalid hour: $invalid", invalid.isValid)
        }
    }

    @Test
    fun dayValidityNeedsADatePlausibleMaximumAndReadableSunTimes() {
        val valid = ForecastDay("2026-10-02", 22.4, "2026-10-02T06:58", "2026-10-02T19:26")
        assertTrue(valid.isValid)
        assertEquals(day, valid.localDate)
        assertNull(valid.copy(date = "2 Oct 2026").localDate)
        listOf(valid.copy(date = "2 Oct 2026"), valid.copy(maxTemperature = Double.NaN),
            valid.copy(maxTemperature = 65.1), valid.copy(maxTemperature = -60.1),
            valid.copy(sunrise = "06:58"), valid.copy(sunset = ""),
            valid.copy(sunrise = "2026-10-03T06:58"), valid.copy(sunset = "2026-10-02T05:00")).forEach { invalid ->
            assertFalse("Invalid day: $invalid", invalid.isValid)
        }
    }

    @Test
    fun ageIsUnknownBeforeAFetchOrWhenTheClockIsBehindIt() {
        assertEquals(0L, snapshot.ageMillis(fetchedAt))
        assertEquals(minutes(14), snapshot.ageMillis(fetchedAt + minutes(14)))
        assertNull(snapshot.ageMillis(fetchedAt - 1))
        assertNull(snapshot.copy(fetchedAt = 0).ageMillis(fetchedAt))
    }

    @Test
    fun freshnessFollowsAgeAndWhetherTheForecastCoversToday() {
        assertEquals(Freshness.FRESH, snapshot.freshness(fetchedAt, day))
        assertEquals(Freshness.FRESH, snapshot.freshness(fetchedAt + minutes(59), day))
        assertEquals(Freshness.AGING, snapshot.freshness(fetchedAt + minutes(60), day))
        assertEquals(Freshness.AGING, snapshot.freshness(fetchedAt + minutes(5 * 60 + 59), day))
        assertEquals(Freshness.AGING, snapshot.freshness(fetchedAt + minutes(6 * 60), day))
        assertEquals(Freshness.STALE, snapshot.freshness(fetchedAt + minutes(6 * 60 + 1), day))
        // A cache from yesterday, or one holding only tomorrow, never stands in for today.
        assertEquals(Freshness.STALE, snapshot.freshness(fetchedAt + minutes(10), day.plusDays(1)))
        val tomorrowOnly = snapshot.copy(hours = snapshot.hours.map { it.copy(time = it.localTime!!.plusDays(1).toString()) })
        assertEquals(Freshness.STALE, tomorrowOnly.freshness(fetchedAt + minutes(10), day))
        // The phone clock is behind the fetch time, so the age can't be trusted.
        assertEquals(Freshness.STALE, snapshot.freshness(fetchedAt - 1, day))
        assertEquals(Freshness.MISSING, snapshot.copy(fetchedAt = 0).freshness(fetchedAt, day))
        assertEquals(Freshness.MISSING, snapshot.copy(hours = emptyList()).freshness(fetchedAt, day))
        // Plan save keeps its stricter 60-minute rule.
        assertTrue(snapshot.isFresh(fetchedAt + minutes(59)))
        assertFalse(snapshot.isFresh(fetchedAt + minutes(60)))
    }

    // Open-Meteo payloads: merge on timestamps and never invent a missing value.
    private val times = (8..11).map { day.atTime(it, 0).toString() }
    private val weather = ForecastResponse("Australia/Melbourne", WeatherHours(times,
        listOf(24.0, 25.0, 26.0, 27.0), listOf(50.0, 51.0, 52.0, 53.0), null, wind = listOf(2.0, 3.0, 4.0, 5.0)))
    private val ultraviolet = ForecastResponse("Australia/Melbourne", WeatherHours(times, null, null,
        listOf(4.0, 5.0, 6.0, 7.0), uvClearSky = listOf(4.5, 5.5, 6.5, 7.5)))

    @Test
    fun ultravioletIsJoinedByTimestampAndMissingHoursAreNotInvented() {
        val uv = ultraviolet.copy(hourly = WeatherHours(times.reversed(), null, null, listOf(null, 6.0, 5.0, 4.0),
            uvClearSky = listOf(null, 6.5, 5.5, 4.5)))
        val merged = WeatherRepository.merge(weather, uv)
        assertEquals(3, merged.size)
        assertEquals(listOf(4.0, 5.0, 6.0), merged.map { it.uv })
        assertEquals(listOf(4.5, 5.5, 6.5), merged.map { it.uvClearSky })
        assertEquals(listOf(24.0, 25.0, 26.0), merged.map { it.temperature })
        assertEquals(listOf(50.0, 51.0, 52.0), merged.map { it.humidity })
        assertEquals(listOf(2.0, 3.0, 4.0), merged.map { it.windSpeed })
        assertEquals(times.take(3), merged.map { it.time })
    }

    @Test
    fun mergeRejectsMissingFieldsMismatchedArraysAndWrongTimezones() {
        val weatherHours = weather.hourly!!
        val uvHours = ultraviolet.hourly!!
        val invalidPayloads = listOf(
            "weather timezone" to (weather.copy(timezone = "UTC") to ultraviolet),
            "UV timezone" to (weather to ultraviolet.copy(timezone = "UTC")),
            "offsets differ" to (weather.copy(utcOffsetSeconds = 36_000) to ultraviolet.copy(utcOffsetSeconds = 39_600)),
            "UV offset missing" to (weather.copy(utcOffsetSeconds = 36_000) to ultraviolet),
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
        val separatedTimes = listOf(8, 10, 12, 14).map { day.atTime(it, 0).toString() }
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
            weather.copy(hourly = weatherHours.copy(wind = listOf(2.0, 3.0, 4.0, 80.1))) to ultraviolet,
            weather.copy(hourly = weatherHours.copy(wind = listOf(2.0, 3.0, 4.0, -0.1))) to ultraviolet,
            weather to ultraviolet.copy(hourly = uvHours.copy(uv = listOf(4.0, 5.0, 6.0, 30.1))),
            weather to ultraviolet.copy(hourly = uvHours.copy(uvClearSky = listOf(4.5, 5.5, 6.5, 30.1)))
        )
        invalidPayloads.forEach { (conditions, uv) ->
            val merged = WeatherRepository.merge(conditions, uv)
            assertEquals(times.take(3), merged.map { it.time })
            assertTrue(merged.all { it.isValid })
        }
    }

    @Test
    fun windIsNeededForEachHourButClearSkyUvMayBeNull() {
        val weatherHours = requireNotNull(weather.hourly)
        val uvHours = requireNotNull(ultraviolet.hourly)
        listOf(listOf(2.0, 3.0, 4.0, null), listOf(2.0, 3.0, 4.0)).forEach { wind ->
            val merged = WeatherRepository.merge(weather.copy(hourly = weatherHours.copy(wind = wind)), ultraviolet)
            assertEquals("An hour without wind is skipped: $wind", times.take(3), merged.map { it.time })
        }
        assertThrows("No wind at all leaves no complete interval", IllegalArgumentException::class.java) {
            WeatherRepository.merge(weather.copy(hourly = weatherHours.copy(wind = null)), ultraviolet)
        }
        val tailNulls = WeatherRepository.merge(weather,
            ultraviolet.copy(hourly = uvHours.copy(uvClearSky = listOf(4.5, 5.5, null, null))))
        assertEquals(times, tailNulls.map { it.time })
        assertEquals(listOf(4.5, 5.5, null, null), tailNulls.map { it.uvClearSky })
        assertEquals(listOf(4.0, 5.0, 6.0, 7.0), tailNulls.map { it.uv })
        val noClearSky = WeatherRepository.merge(weather, ultraviolet.copy(hourly = uvHours.copy(uvClearSky = null)))
        assertEquals(times, noClearSky.map { it.time })
        assertTrue(noClearSky.all { it.uvClearSky == null })
    }

    @Test
    fun daysAreValidatedSortedAndNeverGuessed() {
        val daily = WeatherDays(
            listOf("2026-10-04", "2026-10-02", "2026-10-03", "not-a-date", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08"),
            listOf(30.5, 22.4, 27.9, 25.0, Double.NaN, null, 70.0, 20.0),
            listOf("2026-10-04T06:55", "2026-10-02T06:58", "dawn", "2026-10-05T06:54", "2026-10-05T06:54",
                "2026-10-06T06:53", "2026-10-07T06:51", "2026-10-08T06:50"),
            listOf("2026-10-04T19:28", "2026-10-02T19:26", "2026-10-03T19:27", "2026-10-05T19:29", "2026-10-05T19:29",
                "2026-10-06T19:30", "2026-10-07T19:31"))
        val days = WeatherRepository.parseDays(weather.copy(daily = daily))
        assertEquals(listOf("2026-10-02", "2026-10-04"), days.map { it.date })
        assertEquals(listOf(22.4, 30.5), days.map { it.maxTemperature })
        assertEquals(listOf("2026-10-02T06:58", "2026-10-04T06:55"), days.map { it.sunrise })
        assertEquals(listOf("2026-10-02T19:26", "2026-10-04T19:28"), days.map { it.sunset })
        val repeated = WeatherDays(listOf("2026-10-02", "2026-10-02", "2026-10-03"), listOf(22.4, 23.0, 27.9),
            listOf("2026-10-02T06:58", "2026-10-02T06:58", "2026-10-03T06:57"),
            listOf("2026-10-02T19:26", "2026-10-02T19:26", "2026-10-03T19:27"))
        assertEquals(listOf("2026-10-03"), WeatherRepository.parseDays(weather.copy(daily = repeated)).map { it.date })
        assertTrue(WeatherRepository.parseDays(weather).isEmpty())
        assertTrue(WeatherRepository.parseDays(weather.copy(daily = daily.copy(time = null))).isEmpty())
    }

    // Daylight saving: Open-Meteo labels every hour at the one UTC offset in force when it answered.
    @Test
    fun labelsBecomeMelbourneTimeSoTheDaylightSavingStartDayHas23Hours() {
        // Asked on 3 Oct, before clocks go forward: 4 Oct is labelled at +10 and still has a 02:00 label.
        val (conditions, uv) = labelledAt(36_000, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5))
        val merged = WeatherRepository.merge(conditions, uv)
        assertEquals(72, merged.size)
        assertEquals(merged.map { it.time }.distinct().sorted(), merged.map { it.time })
        val dstDay = merged.filter { it.localTime?.toLocalDate() == LocalDate.of(2026, 10, 4) }
        assertEquals(23, dstDay.size)
        assertEquals((0..23).filter { it != 2 }, dstDay.map { it.localTime!!.hour })
        // 3 Oct and 01:00 on 4 Oct keep their labels; from the 02:00 label on, each reading belongs an hour later.
        assertEquals(labelAir("2026-10-03T13:00"), merged.at("2026-10-03T13:00").temperature, 1e-9)
        assertEquals(labelAir("2026-10-04T01:00"), merged.at("2026-10-04T01:00").temperature, 1e-9)
        assertEquals(labelAir("2026-10-04T02:00"), merged.at("2026-10-04T03:00").temperature, 1e-9)
        assertEquals(labelAir("2026-10-04T13:00"), merged.at("2026-10-04T14:00").temperature, 1e-9)
        assertEquals("2026-10-06T00:00", merged.last().time)
        // Sunrise and sunset carry the same offset (live values for 3–5 Oct 2026): 5 Oct's 05:50 is 06:50 on the clock.
        val sun = conditions.copy(daily = WeatherDays(listOf("2026-10-03", "2026-10-04", "2026-10-05"),
            listOf(19.4, 14.8, 15.9), listOf("2026-10-03T05:53", "2026-10-04T05:51", "2026-10-05T05:50"),
            listOf("2026-10-03T18:26", "2026-10-04T18:26", "2026-10-05T18:27")))
        val days = WeatherRepository.parseDays(sun)
        assertEquals(listOf("2026-10-03", "2026-10-04", "2026-10-05"), days.map { it.date })
        assertEquals(listOf("2026-10-03T05:53", "2026-10-04T06:51", "2026-10-05T06:50"), days.map { it.sunrise })
        assertEquals(listOf("2026-10-03T18:26", "2026-10-04T19:26", "2026-10-05T19:27"), days.map { it.sunset })
    }

    @Test
    fun theDaylightSavingEndDayKeepsOneTwoAmWhateverTheArrayOrder() {
        // Asked on 3 Apr 2027, before clocks go back: 4 Apr is labelled at +11, so two labels fall in the repeated 02:00.
        val (conditions, uv) = labelledAt(39_600, LocalDate.of(2027, 4, 3), LocalDate.of(2027, 4, 5))
        val merged = WeatherRepository.merge(conditions, uv)
        assertEquals(71, merged.size)
        val endDay = merged.filter { it.localTime?.toLocalDate() == LocalDate.of(2027, 4, 4) }
        assertEquals((0..23).toList(), endDay.map { it.localTime!!.hour })
        // The first 02:00 (still daylight time) stays; the reading labelled 04:00 is the real 03:00.
        assertEquals(labelAir("2027-04-04T02:00"), merged.at("2027-04-04T02:00").temperature, 1e-9)
        assertEquals(labelAir("2027-04-04T04:00"), merged.at("2027-04-04T03:00").temperature, 1e-9)
        assertEquals(labelAir("2027-04-05T00:00"), merged.at("2027-04-04T23:00").temperature, 1e-9)
        // Array order never decides which 02:00 stays.
        val hours = requireNotNull(conditions.hourly)
        val reversed = conditions.copy(hourly = hours.copy(time = hours.time!!.reversed(),
            temperature = hours.temperature!!.reversed(), humidity = hours.humidity!!.reversed(), wind = hours.wind!!.reversed()))
        assertEquals(merged, WeatherRepository.merge(reversed, uv))
    }

    // The cache: days and the refresh source survive, and corrupt days never hide the hours.
    @Test
    fun legacyForecastFailuresRestoreAsStableKindsAndCorruptHoursStayDistinct() {
        val cache = ForecastCache("Birrarung Marr", fetchedAt, "[]", fetchedAt, "failed")
        val legacy = mapOf(
            "The forecast service didn't answer. Retry in a few minutes." to "forecast_service",
            "Forecast could not be reached. Check your connection and retry." to "forecast_connection",
            "The forecast came back incomplete. Retry." to "forecast_incomplete",
            "The saved forecast couldn't be read. Retry." to "forecast_saved_unreadable")
        legacy.forEach { (message, kind) ->
            assertEquals(kind, WeatherRepository.snapshotOf(cache.copy(error = message)).error)
            assertEquals(kind, WeatherRepository.snapshotOf(cache.copy(error = kind)).error)
        }
        assertEquals("forecast_saved_unreadable", WeatherRepository.snapshotOf(cache.copy(hoursJson = "not json")).error)
        assertEquals("forecast_incomplete", WeatherRepository.snapshotOf(cache.copy(error = "unknown old failure")).error)
    }

    @Test
    fun cacheRestoresDaysAndWhoRefreshedWhileCorruptDaysLeaveHoursIntact() {
        val gson = Gson()
        val days = listOf(ForecastDay("2026-10-02", 22.4, "2026-10-02T06:58", "2026-10-02T19:26"))
        val cache = ForecastCache("Birrarung Marr", fetchedAt, gson.toJson(snapshot.hours), fetchedAt, "success",
            daysJson = gson.toJson(days), refreshedBy = ForecastSnapshot.REFRESHED_BY_BACKGROUND)
        val restored = WeatherRepository.snapshotOf(cache)
        assertEquals(snapshot.hours, restored.hours)
        assertEquals(days, restored.days)
        assertEquals(ForecastSnapshot.REFRESHED_BY_BACKGROUND, restored.refreshedBy)
        assertEquals("success", restored.outcome)
        assertNull(restored.error)
        listOf("not json", "{}",
            """[{"date":"2026-10-02","maxTemperature":99.0,"sunrise":"2026-10-02T06:58","sunset":"2026-10-02T19:26"}]""")
            .forEach { corrupt ->
                val partial = WeatherRepository.snapshotOf(cache.copy(daysJson = corrupt))
                assertEquals("Hours survive corrupt days: $corrupt", snapshot.hours, partial.hours)
                assertTrue(partial.days.isEmpty())
                assertEquals("success", partial.outcome)
                assertNull(partial.error)
            }
    }

    @Test
    fun olderCacheRowsWithoutWindOrDaysStillRead() {
        val legacy = ForecastCache("Birrarung Marr", fetchedAt,
            """[{"time":"2026-10-02T09:00","temperature":26.0,"humidity":45.0,"uv":3.0}]""", fetchedAt, "success")
        val restored = WeatherRepository.snapshotOf(legacy)
        assertEquals(listOf(ForecastHour("2026-10-02T09:00", 26.0, 45.0, 3.0, 0.0, null)), restored.hours)
        assertTrue(restored.days.isEmpty())
        assertEquals(ForecastSnapshot.REFRESHED_BY_APP, restored.refreshedBy)
    }

    @Test
    fun failedRefreshKeepsTheLastPayloadItsAgeAndWhoFetchedIt() {
        val gson = Gson()
        val previous = ForecastCache("Royal Park", fetchedAt, gson.toJson(snapshot.hours), fetchedAt, "success", null,
            gson.toJson(listOf(ForecastDay("2026-10-02", 22.4, "2026-10-02T06:58", "2026-10-02T19:26"))),
            ForecastSnapshot.REFRESHED_BY_BACKGROUND)
        val failed = WeatherRepository.failedCache("Royal Park", previous, fetchedAt + minutes(30), heatshield.data.ForecastFailure.CONNECTION,
            ForecastSnapshot.REFRESHED_BY_APP)
        assertEquals(previous.copy(lastAttempt = fetchedAt + minutes(30), outcome = "failed", error = "forecast_connection"), failed)
        val restored = WeatherRepository.snapshotOf(failed)
        assertEquals(fetchedAt, restored.lastSuccess)
        assertEquals(1, restored.days.size)
        assertEquals(ForecastSnapshot.REFRESHED_BY_BACKGROUND, restored.refreshedBy)
        assertEquals("forecast_connection", restored.error)
        assertEquals(Freshness.FRESH, restored.freshness(fetchedAt + minutes(30), day))

        val first = WeatherRepository.failedCache("Royal Park", null, fetchedAt, heatshield.data.ForecastFailure.CONNECTION,
            ForecastSnapshot.REFRESHED_BY_BACKGROUND)
        assertEquals(ForecastCache("Royal Park", 0, "[]", fetchedAt, "failed", "forecast_connection", "[]",
            ForecastSnapshot.REFRESHED_BY_BACKGROUND), first)
        assertEquals(Freshness.MISSING, WeatherRepository.snapshotOf(first).freshness(fetchedAt, day))
    }

    // Requests: the exact parameters Open-Meteo receives, answered by a canned payload with no network.
    @Test
    fun requestsAskForWindInMetresPerSecondDailyValuesAndClearSkyUv() {
        val urls = CopyOnWriteArrayList<HttpUrl>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            urls += request.url
            val body = if (request.url.host == "air-quality-api.open-meteo.com") AIR_QUALITY_JSON else FORECAST_JSON
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        try {
            val api = WeatherRepository.createApi(client)
            val (conditions, uv) = runBlocking {
                api.forecast(-37.8185931, 144.9716404) to api.ultraviolet(-37.8185931, 144.9716404)
            }
            val forecastUrl = urls.single { it.host == "api.open-meteo.com" }
            assertEquals("/v1/forecast", forecastUrl.encodedPath)
            assertEquals("temperature_2m,relative_humidity_2m,wind_speed_10m", forecastUrl.queryParameter("hourly"))
            assertEquals("temperature_2m_max,sunrise,sunset", forecastUrl.queryParameter("daily"))
            assertEquals("ms", forecastUrl.queryParameter("wind_speed_unit"))
            assertEquals("Australia/Melbourne", forecastUrl.queryParameter("timezone"))
            assertEquals(ForecastSnapshot.FORECAST_DAYS.toString(), forecastUrl.queryParameter("forecast_days"))
            // Yesterday too, so a refresh after midnight keeps the hours a night shift began in.
            assertEquals("1", forecastUrl.queryParameter("past_days"))
            val uvUrl = urls.single { it.host == "air-quality-api.open-meteo.com" }
            assertEquals("/v1/air-quality", uvUrl.encodedPath)
            assertEquals("uv_index,uv_index_clear_sky", uvUrl.queryParameter("hourly"))
            assertEquals(ForecastSnapshot.FORECAST_DAYS.toString(), uvUrl.queryParameter("forecast_days"))
            assertEquals("1", uvUrl.queryParameter("past_days"))
            // Both responses state the one offset their labels use.
            assertEquals(36_000, conditions.utcOffsetSeconds)
            assertEquals(36_000, uv.utcOffsetSeconds)

            val hours = WeatherRepository.merge(conditions, uv)
            assertEquals(listOf(2.5, 3.1, 4.0), hours.map { it.windSpeed })
            assertEquals(listOf(4.2, 5.0, null), hours.map { it.uvClearSky })
            assertEquals(listOf("2026-10-02", "2026-10-03"), WeatherRepository.parseDays(conditions).map { it.date })
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * Astra M2 fix round 1, F1: every refresh replaces the cached payload, so a refresh at 00:30 during a 22:00–06:00
     * shift must still hold the evening the shift began in. The canned server answers as Open-Meteo does, from midnight
     * `past_days` ago for `forecast_days` days; the payload then goes through merge, the shift's timing hours, Today and
     * End shift's summary as the app runs them.
     */
    @Test
    fun aRefreshAfterMidnightStillHoldsTheEveningANightShiftBeganIn() {
        val today = LocalDate.of(2026, 1, 17)
        val refreshedAt = today.atTime(0, 30)
        val startedAt = today.minusDays(1).atTime(22, 0)
        // 30° all night (Warm; Hot for heavy work), 34° at 23:00 (Hot; Very hot for heavy work).
        fun air(label: LocalDateTime) = if (label == startedAt.plusHours(1)) 34.0 else 30.0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val past = request.url.queryParameter("past_days")?.toLong() ?: 0L
            val days = requireNotNull(request.url.queryParameter("forecast_days")).toLong()
            val labels = generateSequence(today.minusDays(past).atStartOfDay()) { it.plusHours(1) }
                .takeWhile { it.isBefore(today.plusDays(days).atStartOfDay()) }.toList()
            val hourly: Map<String, Any> = if (request.url.host == "air-quality-api.open-meteo.com") {
                mapOf("time" to labels.map { it.toString() }, "uv_index" to labels.map { 0.0 }, "uv_index_clear_sky" to labels.map { 0.0 })
            } else {
                mapOf("time" to labels.map { it.toString() }, "temperature_2m" to labels.map(::air),
                    "relative_humidity_2m" to labels.map { 10.0 }, "wind_speed_10m" to labels.map { 8.0 })
            }
            val body = Gson().toJson(mapOf("utc_offset_seconds" to 39_600, "timezone" to "Australia/Melbourne", "hourly" to hourly))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        try {
            val api = WeatherRepository.createApi(client)
            val hours = runBlocking { WeatherRepository.merge(api.forecast(-37.8185931, 144.9716404), api.ultraviolet(-37.8185931, 144.9716404)) }
            val nowMillis = ShiftRules.epochMillis(refreshedAt)
            val snapshot = ForecastSnapshot("Birrarung Marr", nowMillis, hours)
            val night = WorkSession(ownerId = "local", title = "Push mowing", site = "Birrarung Marr", date = "16 Jan 2026",
                outdoorMinutes = 0, breakMinutes = 0, status = ShiftStatus.NONE, startedAt = ShiftRules.epochMillis(startedAt),
                plannedEndAt = ShiftRules.epochMillis(today.atTime(6, 0)), source = ShiftSource.TRACKED, heavyWork = true)
            val profile = ContextReasoner.effectiveProfile(UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true), null, today, night)
            // The break is timed from the 22:00 start (Hot for heavy work: 45 min), not "can't time breaks" all night.
            val evaluation = ShiftRules.evaluate(OpenShift(night, emptyList()), ContextReasoner.shiftTimingHours(snapshot, profile, nowMillis),
                BreakRule.HEATSHIELD, 570, 720, ShiftRules.instant(refreshedAt))
            assertEquals(ShiftRules.instant(startedAt.plusMinutes(45)), evaluation.due.dueAt)
            // Today reads the shift's own hours from 22:00 yesterday, all of them.
            val heat = ContextReasoner.todayHeat(snapshot, profile, Clock.fixed(Instant.ofEpochMilli(nowMillis), MelbourneZone))
            assertEquals(startedAt, heat.hours.first().time)
            assertTrue("${heat.coverage}", heat.coverage.complete)
            // End shift at 00:30: every minute since 22:00 has its level, and the 23:00 peak is the evening's.
            val totals = ShiftRules.summarise(ShiftRules.instant(startedAt), ShiftRules.instant(refreshedAt), emptyList(),
                ContextReasoner.shiftHours(snapshot, profile, nowMillis))
            assertEquals(150, totals.outdoorMinutes)
            assertEquals(150, totals.minutesByLevel.values.sum())
            assertEquals(startedAt.plusHours(1), totals.peak?.time)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /** The air temperature a test payload gives a label: day of month plus hour / 100, so the 4th at 13:00 reads 4.13. */
    private fun labelAir(label: String): Double = LocalDateTime.parse(label).let { it.dayOfMonth + it.hour / 100.0 }

    /** Weather and UV for [from]..[to], labelled as Open-Meteo labels them: every hour at the one [offsetSeconds]. */
    private fun labelledAt(offsetSeconds: Int, from: LocalDate, to: LocalDate): Pair<ForecastResponse, ForecastResponse> {
        val labels = generateSequence(from.atStartOfDay()) { it.plusHours(1) }
            .takeWhile { !it.toLocalDate().isAfter(to) }.map { it.toString() }.toList()
        val conditions = ForecastResponse("Australia/Melbourne", WeatherHours(labels, labels.map { labelAir(it) },
            labels.map { 50.0 }, null, wind = labels.map { 3.0 }), utcOffsetSeconds = offsetSeconds)
        val uv = ForecastResponse("Australia/Melbourne", WeatherHours(labels, null, null, labels.map { 4.0 },
            uvClearSky = labels.map { 5.0 }), utcOffsetSeconds = offsetSeconds)
        return conditions to uv
    }

    private fun List<ForecastHour>.at(time: String): ForecastHour = single { it.time == time }

    private companion object {
        const val FORECAST_JSON = """{"latitude":-37.82,"longitude":144.97,"utc_offset_seconds":36000,"timezone":"Australia/Melbourne",
            "hourly":{"time":["2026-10-02T09:00","2026-10-02T10:00","2026-10-02T11:00"],
            "temperature_2m":[18.5,20.1,21.4],"relative_humidity_2m":[60,55,50],"wind_speed_10m":[2.5,3.1,4.0]},
            "daily":{"time":["2026-10-02","2026-10-03"],"temperature_2m_max":[22.4,27.9],
            "sunrise":["2026-10-02T06:58","2026-10-03T06:56"],"sunset":["2026-10-02T19:26","2026-10-03T19:27"]}}"""
        const val AIR_QUALITY_JSON = """{"utc_offset_seconds":36000,"timezone":"Australia/Melbourne",
            "hourly":{"time":["2026-10-02T09:00","2026-10-02T10:00","2026-10-02T11:00"],
            "uv_index":[3.9,4.8,5.6],"uv_index_clear_sky":[4.2,5.0,null]}}"""
    }
}
