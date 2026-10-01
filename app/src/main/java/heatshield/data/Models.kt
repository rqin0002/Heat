package heatshield.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import heatshield.domain.WorkTasks
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
    val site: String = "Birrarung Marr",
    val endMinute: Int = 900
) {
    val date: LocalDate get() = Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val isValid: Boolean get() = work.isNotBlank() && work.length <= 100 && startHour in 0..22 && startMinute in 0..59 &&
        endMinute in (startHour * 60 + startMinute + 60)..1439 &&
        effort in listOf("Light effort", "Moderate effort", "Heavy effort") &&
        clothing in listOf("Standard workwear", "Heavy protective clothing") && site.isNotBlank() && site.length <= 100
}

/** Stored follow-up values. Screens show statusLabel(), never these. */
object ShiftStatus {
    const val NONE = "none"
    const val TO_RAISE = "to_raise"
    const val RAISED = "raised"
    val all: List<String> = listOf(NONE, TO_RAISE, RAISED)
}

/** Where a shift's times came from: the clock, the Add a past shift pickers, or the labelled preview day. */
object ShiftSource {
    const val TRACKED = "tracked"
    const val MANUAL = "manual"
    const val PREVIEW = "preview"
    val all: List<String> = listOf(TRACKED, MANUAL, PREVIEW)
}

object ShiftEventType {
    const val BREAK = "break"
    const val SMOKO = "smoko"
    const val LUNCH = "lunch"
    const val CANT_STOP = "cant_stop"
    /** A posted break alert (M3): zero-length, with the level, heat and due time it announced. Never a break, never an answer. */
    const val ALERT = "alert"
    /** The only types that pause or reset the work clock and count as break minutes. */
    val breaks: List<String> = listOf(BREAK, SMOKO, LUNCH)
    val all: List<String> = listOf(BREAK, SMOKO, LUNCH, CANT_STOP, ALERT)
}

/** `reason` of an `alert` row: the first alert for a due time, or its single repeat two minutes later. */
object AlertReason {
    const val BREAK_DUE = "break_due"
    const val REPEAT = "repeat"
    val all: List<String> = listOf(BREAK_DUE, REPEAT)
}

/** Why a break started. `no_shade` also flags the shift To raise; `unwell` opened first aid. */
object BreakReason {
    const val SHADE = "shade"
    const val NO_SHADE = "no_shade"
    const val UNWELL = "unwell"
    val all: List<String> = listOf(SHADE, NO_SHADE, UNWELL)
}

enum class ShiftError { SHIFT_OPEN, NO_SHIFT, BREAK_OPEN, NO_BREAK, INVALID_TIMES, IN_FUTURE, TOO_LONG, SHIFT_GONE, OVERLAPS }
class ShiftFailure(val error: ShiftError) : IllegalStateException(error.name)

/**
 * One shift in the log. Legacy rows (v1/v2) have no times: their minutes were typed. From v3 the clock or the pickers
 * set [startedAt]/[endedAt] and every minute column is derived by ShiftRules.summarise. [startedElapsed] and [bootCount]
 * anchor the shift to SystemClock.elapsedRealtime so the same boot counts monotonically and a reboot falls back to wall time.
 */
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
    val checkInNote: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val plannedEndAt: Long? = null,
    @ColumnInfo(defaultValue = "manual") val source: String = ShiftSource.MANUAL,
    @ColumnInfo(defaultValue = "0") val heavyWork: Boolean = false,
    @ColumnInfo(defaultValue = "0") val heavyClothing: Boolean = false,
    val peakHeat: Double? = null,
    val peakLevel: String? = null,
    @ColumnInfo(defaultValue = "0") val normalMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val warmMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val hotMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val veryHotMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val extremeMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val longestStretchMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val autoEnded: Boolean = false,
    val startedElapsed: Long? = null,
    val bootCount: Int? = null,
    val note: String? = null
) {
    @get:Ignore
    val isOpen: Boolean get() = startedAt != null && endedAt == null
}

/**
 * One thing that happened on a shift. Breaks (tapped, or confirmed smoko/lunch) have a start and, once closed, an end;
 * a skipped smoko/lunch is stored with [skipped] so it is never proposed again; a "Can't stop yet" answer starts and
 * ends at the same instant and adds no minutes. Times are epoch millis on the shift's own timeline.
 */
@Entity(
    tableName = "shift_events",
    foreignKeys = [ForeignKey(entity = WorkSession::class, parentColumns = ["id"], childColumns = ["shiftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("shiftId")]
)
data class ShiftEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shiftId: Int,
    val type: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startedElapsed: Long = 0,
    val bootCount: Int = 0,
    val reason: String? = null,
    @ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
    val level: String? = null,
    val heatC: Double? = null,
    val dueAt: Long? = null
)

/** Breaks (tapped, smoko, lunch) are the only events the clock reads; answers and alerts are history beside them. */
val ShiftEvent.isBreak: Boolean get() = type in ShiftEventType.breaks
val ShiftEvent.isAnswer: Boolean get() = type == ShiftEventType.CANT_STOP

/** Column defaults are what an upgraded v1 row receives; Kotlin defaults are what a new owner starts with. */
@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val ownerId: String,
    val site: String = "Birrarung Marr",
    val effort: String = "Heavy effort",
    val clothing: String = "Standard workwear",
    val reminders: Boolean = false,
    @ColumnInfo(defaultValue = WorkTasks.DEFAULT_TASK_NAME) val task: String = WorkTasks.DEFAULT_TASK_NAME,
    @ColumnInfo(defaultValue = "420") val shiftStartMinute: Int = 420,
    @ColumnInfo(defaultValue = "900") val shiftEndMinute: Int = 900,
    @ColumnInfo(defaultValue = "35.0") val hotDayTrigger: Double = 35.0,
    val acclimatisingUntil: Long? = null, // epoch millis; null = not acclimatising
    @ColumnInfo(defaultValue = "0") val setupDone: Boolean = false,
    @ColumnInfo(defaultValue = "570") val smokoMinute: Int = 570,
    @ColumnInfo(defaultValue = "720") val lunchMinute: Int = 720,
    @ColumnInfo(defaultValue = "heatshield") val breakRule: String = "heatshield"
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
    val revision: Long = 0,
    @ColumnInfo(defaultValue = "900") val endMinute: Int = 900
) {
    @get:Ignore
    val context: WorkContext get() = WorkContext(work, effort, clothing, dayMillis, startHour, startMinute, site, endMinute)
}

/** One forecast hour. Wind is m/s. Clear-sky UV may be null: the live API ends that array with nulls. */
data class ForecastHour(
    val time: String,
    val temperature: Double,
    val humidity: Double,
    val uv: Double,
    val windSpeed: Double = 0.0,
    val uvClearSky: Double? = null
) {
    val localTime: LocalDateTime? get() = runCatching { LocalDateTime.parse(time) }.getOrNull()
    val isValid: Boolean get() = localTime?.let { it.minute == 0 && it.second == 0 && it.nano == 0 } == true &&
        temperature.isFinite() && temperature in -60.0..65.0 &&
        humidity.isFinite() && humidity in 0.0..100.0 && uv.isFinite() && uv in 0.0..30.0 &&
        windSpeed.isFinite() && windSpeed in 0.0..80.0 &&
        (uvClearSky == null || uvClearSky.isFinite() && uvClearSky in 0.0..30.0)
}

/** One forecast day: maximum air temperature and local sunrise and sunset, as Open-Meteo returns them. */
data class ForecastDay(val date: String, val maxTemperature: Double, val sunrise: String, val sunset: String) {
    val localDate: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
    val isValid: Boolean get() = localDate != null && maxTemperature.isFinite() && maxTemperature in -60.0..65.0 &&
        runCatching {
            val rise = LocalDateTime.parse(sunrise)
            val set = LocalDateTime.parse(sunset)
            rise.toLocalDate() == localDate && set.toLocalDate() == localDate && rise.isBefore(set)
        }.getOrDefault(false)
}

/** How far Today can lean on a forecast (spec §5.13). */
enum class Freshness { FRESH, AGING, STALE, MISSING }

data class ForecastSnapshot(
    val site: String,
    val fetchedAt: Long,
    val hours: List<ForecastHour>,
    val error: String? = null,
    val lastAttempt: Long = fetchedAt,
    val outcome: String = "success",
    val days: List<ForecastDay> = emptyList(),
    val refreshedBy: String = REFRESHED_BY_APP
) {
    val lastSuccess: Long get() = fetchedAt

    /** Plan save keeps the A2 rule: a forecast under 60 minutes old. */
    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        fetchedAt > 0 && nowMillis >= fetchedAt && nowMillis - fetchedAt < MAX_AGE_MILLIS && hours.isNotEmpty()

    /** Null before the first successful fetch, or when the phone clock is behind the fetch time. */
    fun ageMillis(nowMillis: Long): Long? = if (fetchedAt <= 0 || nowMillis < fetchedAt) null else nowMillis - fetchedAt

    /** Fresh under 1 h, aging up to 6 h, then stale. A forecast with no hour on [today] is stale at any age. */
    fun freshness(nowMillis: Long, today: LocalDate): Freshness {
        if (fetchedAt <= 0 || hours.isEmpty()) return Freshness.MISSING
        // A fetch time ahead of the phone clock can't be aged, so it isn't trusted.
        val age = ageMillis(nowMillis) ?: return Freshness.STALE
        return when {
            age > STALE_AFTER_MILLIS || hours.none { it.localTime?.toLocalDate() == today } -> Freshness.STALE
            age >= AGING_AFTER_MILLIS -> Freshness.AGING
            else -> Freshness.FRESH
        }
    }

    companion object {
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L
        const val AGING_AFTER_MILLIS = 60 * 60 * 1000L
        const val STALE_AFTER_MILLIS = 6 * 60 * 60 * 1000L
        const val REFRESHED_BY_APP = "app"
        const val REFRESHED_BY_BACKGROUND = "background"
        /** Debug builds only: the demo fixture's invented hours (DemoDataReceiver). Today never stamps them as Open-Meteo's. */
        const val REFRESHED_BY_DEMO = "demo"
        const val FORECAST_DAYS = 5
        /**
         * Every refresh also asks for yesterday's hours, because a refresh replaces the cache. From midnight yesterday
         * the cache reaches at least a whole day back, more than the longest shift (16 hours), so a refresh after
         * midnight never drops the evening a night shift began in.
         */
        const val PAST_DAYS = 1
    }
}

@Entity(tableName = "forecast_cache")
data class ForecastCache(
    @PrimaryKey val site: String,
    val fetchedAt: Long,
    val hoursJson: String,
    val lastAttempt: Long,
    val outcome: String,
    val error: String? = null,
    @ColumnInfo(defaultValue = "[]") val daysJson: String = "[]",
    @ColumnInfo(defaultValue = "app") val refreshedBy: String = ForecastSnapshot.REFRESHED_BY_APP
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
