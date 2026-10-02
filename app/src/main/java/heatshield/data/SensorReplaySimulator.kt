package heatshield.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.Reader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** One row of the historical file, stamped with the moment it was played. */
data class IndexedReading(val index: Int, val reading: SensorReading, val emittedAtMillis: Long)

/**
 * Plays a historical City of Melbourne sensor file as a timed stream for Preview.
 * It is never a current device sensor and never feeds Today.
 */
class SensorReplaySimulator(
    private val source: suspend () -> List<SensorReading>,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    @Volatile private var loaded: List<SensorReading>? = null

    suspend fun rows(): List<SensorReading> = loaded ?: source().also { loaded = it }

    /** Emits [startIndex] at once, then the next row every [intervalMillis], looping to the first row after the last. */
    fun readings(startIndex: Int): Flow<IndexedReading> = flow {
        val all = rows()
        require(all.isNotEmpty()) { "The sensor file has no readings." }
        var index = startIndex.mod(all.size)
        while (true) {
            val emittedAt = nowMillis()
            emit(IndexedReading(index, all[index].copy(emittedAt = emittedAt), emittedAt))
            delay(intervalMillis)
            index = (index + 1) % all.size
        }
    }

    companion object {
        /** Observations are about 15 minutes apart, so one every 20 s plays the day 45 times faster. */
        const val INTERVAL_MILLIS = 20_000L
        const val VIRTUAL_SPEEDUP = 45
        val PREVIEW_DATE: LocalDate = LocalDate.of(2026, 1, 9)
        val PREVIEW_START: LocalTime = LocalTime.of(9, 52)
        const val SOURCE_URL = "https://data.melbourne.vic.gov.au/explore/dataset/microclimate-sensors-data/information/"
        const val LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
        private const val ASSET = "microclimate_replay.csv"
        private const val HEADER = "source_id,device_id,observed_at_utc,observed_at_local,temperature_c,relative_humidity_pct,latitude,longitude,source_location,provenance"

        fun fromAssets(context: Context): SensorReplaySimulator {
            val assets = context.applicationContext.assets
            return SensorReplaySimulator({
                withContext(Dispatchers.IO) { assets.open(ASSET).bufferedReader().use { parse(it) } }
            })
        }

        /** Melbourne wall-clock time of the observation. */
        fun localDateTimeOf(reading: SensorReading): LocalDateTime =
            OffsetDateTime.parse(reading.observedAtLocal).atZoneSameInstant(MelbourneZone).toLocalDateTime()

        /** First row on [date] at or after [from]; 0 when the file has none. */
        fun startIndexFor(rows: List<SensorReading>, date: LocalDate, from: LocalTime): Int =
            rows.indexOfFirst { reading ->
                localDateTimeOf(reading).let { it.toLocalDate() == date && !it.toLocalTime().isBefore(from) }
            }.coerceAtLeast(0)

        /**
         * Reads the fixed Birrarung Marr observations, rejecting the whole file if values or provenance are wrong.
         * The park's stable ID keeps display ordering out of this check: Carlton Gardens may be first without
         * changing the expected coordinates. For example, 288 valid Birrarung rows pass; a row at latitude 0 fails.
         */
        internal fun parse(reader: Reader): List<SensorReading> {
            val sensorSite = WorkSite.defaults.single { it.id == "birrarung-marr" }
            val lines = reader.readText().lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.firstOrNull()?.removePrefix("\uFEFF") == HEADER) { "Sensor data columns are not recognised." }
            val readings = lines.drop(1).mapIndexed { index, line ->
                val cells = line.split(',')
                require(cells.size == 10) { "Sensor row ${index + 2} has missing columns." }
                val reading = SensorReading(cells[0], cells[1], cells[2], cells[3],
                    cells[4].toDoubleOrNull() ?: Double.NaN, cells[5].toDoubleOrNull() ?: Double.NaN,
                    cells[6].toDoubleOrNull() ?: Double.NaN, cells[7].toDoubleOrNull() ?: Double.NaN,
                    cells[8], cells[9])
                require(reading.isValid && reading.sourceId == "COM-microclimate-sensors-data" &&
                    reading.deviceId == "ICTMicroclimate-01" &&
                    reading.sourceLocation == "Birrarung Marr Park - Pole 1131" &&
                    reading.latitude == sensorSite.latitude &&
                    reading.longitude == sensorSite.longitude) {
                    "Sensor row ${index + 2} has invalid values or provenance."
                }
                reading
            }
            require(readings.size == 288) { "The historical replay must contain all 288 observations." }
            require(readings.zipWithNext().all { (first, second) ->
                OffsetDateTime.parse(second.observedAt).isAfter(OffsetDateTime.parse(first.observedAt))
            }) { "Sensor observations must be in timestamp order without duplicates." }
            return readings
        }
    }
}
