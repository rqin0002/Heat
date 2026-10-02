package heatshield.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** [utcOffsetSeconds] is the one offset Open-Meteo used for every label in the response, fixed when it answered. */
internal data class ForecastResponse(
    val timezone: String?,
    val hourly: WeatherHours?,
    val daily: WeatherDays? = null,
    @SerializedName("utc_offset_seconds") val utcOffsetSeconds: Int? = null
)
internal data class WeatherHours(
    val time: List<String?>?,
    @SerializedName("temperature_2m") val temperature: List<Double?>?,
    @SerializedName("relative_humidity_2m") val humidity: List<Double?>?,
    @SerializedName("uv_index") val uv: List<Double?>?,
    @SerializedName("wind_speed_10m") val wind: List<Double?>? = null,
    @SerializedName("uv_index_clear_sky") val uvClearSky: List<Double?>? = null
)
internal data class WeatherDays(
    val time: List<String?>?,
    @SerializedName("temperature_2m_max") val maxTemperature: List<Double?>?,
    val sunrise: List<String?>?,
    val sunset: List<String?>?
)

internal interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "temperature_2m,relative_humidity_2m,wind_speed_10m",
        @Query("daily") daily: String = "temperature_2m_max,sunrise,sunset",
        @Query("wind_speed_unit") windSpeedUnit: String = "ms",
        @Query("timezone") timezone: String = "Australia/Melbourne",
        @Query("forecast_days") days: Int = ForecastSnapshot.FORECAST_DAYS,
        @Query("past_days") pastDays: Int = ForecastSnapshot.PAST_DAYS,
        @Query("temperature_unit") temperatureUnit: String = "celsius"
    ): ForecastResponse

    @GET("https://air-quality-api.open-meteo.com/v1/air-quality")
    suspend fun ultraviolet(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "uv_index,uv_index_clear_sky",
        @Query("timezone") timezone: String = "Australia/Melbourne",
        @Query("forecast_days") days: Int = ForecastSnapshot.FORECAST_DAYS,
        @Query("past_days") pastDays: Int = ForecastSnapshot.PAST_DAYS,
        @Query("domains") domains: String = "cams_global"
    ): ForecastResponse
}

class WeatherRepository(context: Context) {
    private val database = HeatShieldDatabase.get(context)
    private val dao = database.dao()

    fun observe(site: String): Flow<ForecastSnapshot?> = dao.observeForecast(site).map { cache -> cache?.let { snapshotOf(it) } }

    /** The cached forecast for [site] right now, for callers outside a Flow. */
    suspend fun snapshot(site: String): ForecastSnapshot? = dao.getForecast(site)?.let(::snapshotOf)

    /**
     * [refreshedBy] records who fetched the stored payload, so Today can add "(background)" to its stamp. A success
     * replaces the whole cached payload, which runs from midnight yesterday ([ForecastSnapshot.PAST_DAYS]): a refresh
     * after midnight keeps the evening a night shift began in, for its break timing, Today's hours and its summary.
     */
    suspend fun refresh(site: WorkSite, refreshedBy: String = ForecastSnapshot.REFRESHED_BY_APP): Result<Unit> = refreshLock.withLock {
        require(site.isValid) { "Choose a valid work site." }
        require(refreshedBy == ForecastSnapshot.REFRESHED_BY_APP || refreshedBy == ForecastSnapshot.REFRESHED_BY_BACKGROUND)
        val attemptedAt = System.currentTimeMillis()
        try {
            val (hours, days) = coroutineScope {
                val weather = async { api.forecast(site.latitude, site.longitude) }
                val uv = async { api.ultraviolet(site.latitude, site.longitude) }
                val conditions = weather.await()
                merge(conditions, uv.await()) to parseDays(conditions)
            }
            val receivedAt = System.currentTimeMillis()
            database.withTransaction {
                val currentSite = dao.getSite(site.name)
                require(currentSite == null || currentSite == site) { "The selected site's coordinates changed. Refresh again." }
                dao.saveForecast(ForecastCache(site.name, receivedAt, gson.toJson(hours), attemptedAt, "success",
                    daysJson = gson.toJson(days), refreshedBy = refreshedBy))
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the last successful payload, its original age and who fetched it, even after a failed refresh.
            val failure = when (error) {
                is HttpException -> ForecastFailure.SERVICE
                is IOException -> ForecastFailure.CONNECTION
                else -> ForecastFailure.INCOMPLETE
            }
            database.withTransaction {
                val currentSite = dao.getSite(site.name)
                if (currentSite == null || currentSite == site) {
                    dao.saveForecast(failedCache(site.name, dao.getForecast(site.name), attemptedAt, failure, refreshedBy))
                }
            }
            Result.failure(error)
        }
    }

    companion object {
        private val refreshLock = Mutex()
        private val gson = Gson()
        private val api = createApi(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build())

        internal fun createApi(client: OkHttpClient): OpenMeteoApi = Retrofit.Builder().baseUrl("https://api.open-meteo.com/")
            .client(client).addConverterFactory(GsonConverterFactory.create(gson)).build().create(OpenMeteoApi::class.java)

        /**
         * Merge on the timestamp, never on array position across two independent endpoints. The result is in time order.
         *
         * Open-Meteo labels every hour at the one UTC offset in force when it answered, even past a daylight-saving
         * change. So each label becomes Melbourne wall-clock time after the join. A cache fetched before 02:00 on the
         * day clocks go forward then has 23 hours that day with no 02:00. On the day they go back, it has one 02:00.
         */
        internal fun merge(weather: ForecastResponse, ultraviolet: ForecastResponse): List<ForecastHour> {
            require(weather.timezone == MelbourneZone.id && ultraviolet.timezone == MelbourneZone.id)
            // The two calls run in parallel. If they straddle a DST change, their labels are an hour apart.
            require(weather.utcOffsetSeconds == ultraviolet.utcOffsetSeconds) {
                "The forecast and UV responses use different UTC offsets."
            }
            val weatherHours = requireNotNull(weather.hourly)
            val uvHours = requireNotNull(ultraviolet.hourly)
            val times = requireNotNull(weatherHours.time)
            val temperatures = requireNotNull(weatherHours.temperature)
            val humidity = requireNotNull(weatherHours.humidity)
            val wind = weatherHours.wind.orEmpty()
            val uvTimes = requireNotNull(uvHours.time)
            val uvValues = requireNotNull(uvHours.uv)
            val clearSky = uvHours.uvClearSky.orEmpty()
            require(times.isNotEmpty() && times.size == temperatures.size && times.size == humidity.size)
            require(uvTimes.isNotEmpty() && uvTimes.size == uvValues.size)
            require(times.distinct().size == times.size && uvTimes.distinct().size == uvTimes.size)
            val uvByTime = uvTimes.zip(uvValues).toMap()
            // Clear-sky UV is optional per hour; the live API ends that array with nulls.
            val clearSkyByTime = uvTimes.withIndex().associate { (index, time) -> time to clearSky.getOrNull(index) }
            val result = times.mapIndexedNotNull { index, time ->
                if (time == null) return@mapIndexedNotNull null
                val temp = temperatures[index] ?: return@mapIndexedNotNull null
                val rh = humidity[index] ?: return@mapIndexedNotNull null
                // Wind feeds "feels like in shade", so an hour without it is skipped, never guessed.
                val windMs = wind.getOrNull(index) ?: return@mapIndexedNotNull null
                // UV joins on the raw label, which both responses share. Only then does the label become Melbourne time.
                val uv = uvByTime[time] ?: return@mapIndexedNotNull null
                val at = inMelbourne(time, weather.utcOffsetSeconds) ?: return@mapIndexedNotNull null
                val hour = ForecastHour(at.toLocalDateTime().toString(), temp, rh, uv, windMs, clearSkyByTime[time])
                    .takeIf { it.isValid } ?: return@mapIndexedNotNull null
                at.toInstant() to hour
            }
                .sortedBy { (instant, _) -> instant }
                .map { (_, hour) -> hour }
                // When DST ends, 02:00 happens twice. A wall-clock hour holds one reading, so the first (daylight) one stays.
                .distinctBy { it.time }
            require(result.windowed(3).any { samples ->
                LocalDateTime.parse(samples[0].time).plusHours(1) == LocalDateTime.parse(samples[1].time) &&
                    LocalDateTime.parse(samples[1].time).plusHours(1) == LocalDateTime.parse(samples[2].time)
            }) { "No complete hourly forecast interval was returned." }
            return result
        }

        /**
         * Daily max, sunrise and sunset. A missing, invalid or repeated day is dropped, never guessed.
         * Sun times carry the same fixed offset as the hourly labels, so they become Melbourne time the same way.
         */
        internal fun parseDays(weather: ForecastResponse): List<ForecastDay> {
            val daily = weather.daily ?: return emptyList()
            val dates = daily.time ?: return emptyList()
            fun wallClock(label: String?): String? =
                label?.let { inMelbourne(it, weather.utcOffsetSeconds) }?.toLocalDateTime()?.toString()
            val valid = dates.mapIndexedNotNull { index, date ->
                ForecastDay(
                    date ?: return@mapIndexedNotNull null,
                    daily.maxTemperature?.getOrNull(index) ?: return@mapIndexedNotNull null,
                    wallClock(daily.sunrise?.getOrNull(index)) ?: return@mapIndexedNotNull null,
                    wallClock(daily.sunset?.getOrNull(index)) ?: return@mapIndexedNotNull null
                ).takeIf { it.isValid }
            }
            val counts = valid.groupingBy { it.date }.eachCount()
            return valid.filter { counts[it.date] == 1 }.sortedBy { it.date }
        }

        /**
         * An Open-Meteo [label] read at the response's fixed [offsetSeconds], as a Melbourne time.
         * With no offset, Melbourne's own rules are used, which leaves every ordinary label unchanged.
         * Null when the label can't be read.
         */
        private fun inMelbourne(label: String, offsetSeconds: Int?): ZonedDateTime? = runCatching {
            val labelled = LocalDateTime.parse(label)
            val offset = offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it) } ?: MelbourneZone.rules.getOffset(labelled)
            labelled.atOffset(offset).atZoneSameInstant(MelbourneZone)
        }.getOrNull()

        /**
         * Restores a cache row. Unreadable days are dropped without touching the hours. Public so the alert coordinator can
         * read a cache row through its own database (a test's disposable one included).
         */
        fun snapshotOf(cache: ForecastCache): ForecastSnapshot = with(cache) {
            val parsed = runCatching {
                gson.fromJson(hoursJson, Array<ForecastHour>::class.java)?.toList()
                    ?.also { values -> require(values.all { it.isValid }) }
                    ?: emptyList()
            }
            val days = runCatching {
                gson.fromJson(daysJson, Array<ForecastDay>::class.java)?.toList()
                    ?.also { values -> require(values.all { it.isValid }) }
                    ?: emptyList()
            }.getOrDefault(emptyList())
            ForecastSnapshot(site, fetchedAt, parsed.getOrDefault(emptyList()),
                if (parsed.isFailure) ForecastFailure.SAVED_UNREADABLE.key else ForecastFailure.fromStored(error)?.key,
                lastAttempt, if (parsed.isFailure) "failed" else outcome, days, refreshedBy)
        }

        /**
         * Keeps the last good payload and saves only a stable failure kind, so future wording edits update old errors too.
         * Example: a connection failure at 08:30 keeps the 08:00 payload's age and saves forecast_connection.
         */
        internal fun failedCache(site: String, previous: ForecastCache?, attemptedAt: Long, failure: ForecastFailure,
                                 refreshedBy: String): ForecastCache =
            ForecastCache(site, previous?.fetchedAt ?: 0, previous?.hoursJson ?: "[]", attemptedAt, "failed", failure.key,
                previous?.daysJson ?: "[]", previous?.refreshedBy ?: refreshedBy)
    }
}
