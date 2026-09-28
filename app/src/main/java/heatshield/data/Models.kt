package heatshield.data

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val MelbourneZone: ZoneId = ZoneId.of("Australia/Melbourne")

@Entity(tableName = "work_sites", indices = [Index(value = ["name"], unique = true)])
data class WorkSite(@PrimaryKey val id: String, val name: String, val latitude: Double, val longitude: Double) {
    @get:Ignore
    val isValid: Boolean get() = id.isNotBlank() && id.length <= 100 && name.isNotBlank() && name.length <= 100 &&
        latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
    companion object {
        val defaults = listOf(
            WorkSite("birrarung-marr", "Birrarung Marr", -37.8185931, 144.9716404),
            WorkSite("carlton-gardens", "Carlton Gardens", -37.8053, 144.9718),
            WorkSite("royal-park", "Royal Park", -37.7903, 144.9519)
        )

        fun byName(name: String): WorkSite? = defaults.find { it.name == name }
    }
}

/** Material date pickers encode the selected calendar date at midnight UTC. */
data class WorkContext(
    val work: String = "Planting beds",
    val effort: String = "Heavy effort",
    val clothing: String = "Standard workwear",
    val dayMillis: Long = LocalDate.now(MelbourneZone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    val startHour: Int = 7,
    val startMinute: Int = 0,
    val site: String = "Birrarung Marr"
) {
    val date: LocalDate get() = Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val isValid: Boolean get() = work.isNotBlank() && startHour in 0..14 && startMinute in 0..59 &&
        effort in listOf("Light effort", "Moderate effort", "Heavy effort") &&
        clothing in listOf("Standard workwear", "Heavy protective clothing") && site.isNotBlank() && site.length <= 100
}

@Entity(tableName = "work_sessions", indices = [Index("ownerId")])
data class WorkSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ownerId: String,
    val title: String,
    val site: String,
    val date: String,
    val outdoorMinutes: Int,
    val breakMinutes: Int,
    val status: String,
    val checkInNote: String? = null
)

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val ownerId: String,
    val site: String = "Birrarung Marr",
    val effort: String = "Heavy effort",
    val clothing: String = "Standard workwear",
    val reminders: Boolean = false
)

@Entity(tableName = "shift_plans")
data class ShiftPlan(
    @PrimaryKey val ownerId: String,
    val work: String = "Planting beds",
    val effort: String = "Heavy effort",
    val clothing: String = "Standard workwear",
    val dayMillis: Long = WorkContext().dayMillis,
    val startHour: Int = 7,
    val startMinute: Int = 0,
    val site: String = "Birrarung Marr",
    val window: String = "",
    val reminderAt: Long? = null,
    val revision: Long = 0
) {
    @get:Ignore
    val context: WorkContext get() = WorkContext(work, effort, clothing, dayMillis, startHour, startMinute, site)
}

data class ForecastHour(val time: String, val temperature: Double, val humidity: Double, val uv: Double) {
    val localTime: LocalDateTime? get() = runCatching { LocalDateTime.parse(time) }.getOrNull()
    val isValid: Boolean get() = localTime?.let { it.minute == 0 && it.second == 0 } == true &&
        temperature.isFinite() && temperature in -60.0..65.0 &&
        humidity.isFinite() && humidity in 0.0..100.0 && uv.isFinite() && uv in 0.0..30.0
}

data class ForecastSnapshot(
    val site: String,
    val fetchedAt: Long,
    val hours: List<ForecastHour>,
    val error: String? = null,
    val lastAttempt: Long = fetchedAt,
    val outcome: String = "success"
) {
    val lastSuccess: Long get() = fetchedAt
    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        fetchedAt > 0 && nowMillis >= fetchedAt && nowMillis - fetchedAt < MAX_AGE_MILLIS && hours.isNotEmpty()

    companion object {
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L
    }
}

@Entity(tableName = "forecast_cache")
data class ForecastCache(
    @PrimaryKey val site: String,
    val fetchedAt: Long,
    val hoursJson: String,
    val lastAttempt: Long,
    val outcome: String,
    val error: String? = null
)

data class SensorReading(
    val sourceId: String,
    val deviceId: String,
    val observedAt: String,
    val observedAtLocal: String,
    val temperature: Double,
    val humidity: Double,
    val latitude: Double,
    val longitude: Double,
    val sourceLocation: String,
    val provenance: String,
    val emittedAt: Long = 0
) {
    val isValid: Boolean get() = temperature.isFinite() && temperature in -60.0..65.0 &&
        humidity.isFinite() && humidity in 0.0..100.0 &&
        latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
        sourceId.isNotBlank() && deviceId.isNotBlank() && sourceLocation.isNotBlank() &&
        provenance == "observed_environmental_sensor_replay" &&
        runCatching { java.time.OffsetDateTime.parse(observedAt).toInstant() ==
            java.time.OffsetDateTime.parse(observedAtLocal).toInstant() }.getOrDefault(false)
}
