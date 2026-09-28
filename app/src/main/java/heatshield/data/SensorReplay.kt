package heatshield.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Reader
import java.time.OffsetDateTime

/** This source is historical environmental data, never a current device sensor. */
class SensorReplay(context: Context) {
    private val assets = context.applicationContext.assets

    suspend fun load(): List<SensorReading> = withContext(Dispatchers.IO) {
        assets.open("microclimate_replay.csv").bufferedReader().use { parse(it) }
    }

    companion object {
        const val INTERVAL_MILLIS = 20_000L
        const val SOURCE_URL = "https://data.melbourne.vic.gov.au/explore/dataset/microclimate-sensors-data/information/"
        const val LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/"
        private const val HEADER = "source_id,device_id,observed_at_utc,observed_at_local,temperature_c,relative_humidity_pct,latitude,longitude,source_location,provenance"

        internal fun parse(reader: Reader): List<SensorReading> {
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
                    reading.latitude == WorkSite.defaults.first().latitude &&
                    reading.longitude == WorkSite.defaults.first().longitude) {
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
