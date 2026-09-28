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
import java.util.concurrent.TimeUnit

internal data class ForecastResponse(val timezone: String?, val hourly: WeatherHours?)
internal data class WeatherHours(
    val time: List<String?>?,
    @SerializedName("temperature_2m") val temperature: List<Double?>?,
    @SerializedName("relative_humidity_2m") val humidity: List<Double?>?,
    @SerializedName("uv_index") val uv: List<Double?>?
)

internal interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "temperature_2m,relative_humidity_2m",
        @Query("timezone") timezone: String = "Australia/Melbourne",
        @Query("forecast_days") days: Int = 5,
        @Query("temperature_unit") temperatureUnit: String = "celsius"
    ): ForecastResponse

    @GET("https://air-quality-api.open-meteo.com/v1/air-quality")
    suspend fun ultraviolet(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "uv_index",
        @Query("timezone") timezone: String = "Australia/Melbourne",
        @Query("forecast_days") days: Int = 5,
        @Query("domains") domains: String = "cams_global"
    ): ForecastResponse
}

class WeatherRepository(context: Context) {
    private val database = HeatShieldDatabase.get(context)
    private val dao = database.dao()

    fun observe(site: String): Flow<ForecastSnapshot?> = dao.observeForecast(site).map { it?.snapshot() }

    suspend fun refresh(site: WorkSite): Result<Unit> = refreshLock.withLock {
        require(site.isValid) { "Choose a valid work site." }
        val attemptedAt = System.currentTimeMillis()
        try {
            val hours = coroutineScope {
                val weather = async { api.forecast(site.latitude, site.longitude) }
                val uv = async { api.ultraviolet(site.latitude, site.longitude) }
                merge(weather.await(), uv.await())
            }
            val receivedAt = System.currentTimeMillis()
            database.withTransaction {
                val currentSite = dao.getSite(site.name)
                require(currentSite == null || currentSite == site) { "The selected site's coordinates changed. Refresh again." }
                dao.saveForecast(ForecastCache(site.name, receivedAt, gson.toJson(hours), attemptedAt, "success"))
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the last successful payload and its original age, even after a failed refresh.
            val message = when (error) {
                is HttpException -> "Forecast service returned ${error.code()}. Try again later."
                is IOException -> "Forecast could not be reached. Check your connection and retry."
                else -> "The forecast response was incomplete or invalid. Retry to update conditions."
            }
            database.withTransaction {
                val currentSite = dao.getSite(site.name)
                if (currentSite == null || currentSite == site) {
                    val previous = dao.getForecast(site.name)
                    dao.saveForecast(ForecastCache(site.name, previous?.fetchedAt ?: 0,
                        previous?.hoursJson ?: "[]", attemptedAt, "failed", message))
                }
            }
            Result.failure(error)
        }
    }

    private fun ForecastCache.snapshot(): ForecastSnapshot {
        val parsed = runCatching {
            gson.fromJson(hoursJson, Array<ForecastHour>::class.java)?.toList()
                ?.also { values -> require(values.all { it.isValid }) }
                ?: emptyList()
        }
        return ForecastSnapshot(site, fetchedAt, parsed.getOrDefault(emptyList()),
            if (parsed.isFailure) "Cached conditions could not be read. Refresh the forecast." else error,
            lastAttempt, if (parsed.isFailure) "failed" else outcome)
    }

    companion object {
        private val refreshLock = Mutex()
        private val gson = Gson()
        private val api = Retrofit.Builder().baseUrl("https://api.open-meteo.com/")
            .client(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create(gson)).build().create(OpenMeteoApi::class.java)

        /** Merge on the timestamp, never on array position across two independent endpoints. */
        internal fun merge(weather: ForecastResponse, ultraviolet: ForecastResponse): List<ForecastHour> {
            require(weather.timezone == MelbourneZone.id && ultraviolet.timezone == MelbourneZone.id)
            val weatherHours = requireNotNull(weather.hourly)
            val uvHours = requireNotNull(ultraviolet.hourly)
            val times = requireNotNull(weatherHours.time)
            val temperatures = requireNotNull(weatherHours.temperature)
            val humidity = requireNotNull(weatherHours.humidity)
            val uvTimes = requireNotNull(uvHours.time)
            val uvValues = requireNotNull(uvHours.uv)
            require(times.isNotEmpty() && times.size == temperatures.size && times.size == humidity.size)
            require(uvTimes.isNotEmpty() && uvTimes.size == uvValues.size)
            require(times.distinct().size == times.size && uvTimes.distinct().size == uvTimes.size)
            val uvByTime = uvTimes.zip(uvValues).toMap()
            val result = times.mapIndexedNotNull { index, time ->
                if (time == null) return@mapIndexedNotNull null
                val temp = temperatures[index] ?: return@mapIndexedNotNull null
                val rh = humidity[index] ?: return@mapIndexedNotNull null
                val uv = uvByTime[time] ?: return@mapIndexedNotNull null
                ForecastHour(time, temp, rh, uv).takeIf { it.isValid }
            }.sortedBy { it.time }
            require(result.windowed(3).any { samples ->
                LocalDateTime.parse(samples[0].time).plusHours(1) == LocalDateTime.parse(samples[1].time) &&
                    LocalDateTime.parse(samples[1].time).plusHours(1) == LocalDateTime.parse(samples[2].time)
            }) { "No complete hourly forecast interval was returned." }
            return result
        }
    }
}
