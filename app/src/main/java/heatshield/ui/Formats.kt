package heatshield.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import heatshield.domain.HeatRules
import heatshield.domain.ShiftRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formats (spec §8). Storage formats such as WorkSession.date stay unchanged. */
object Formats {
    private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    /** 12-hour: "7 am", "7:30 am", "12 pm"; 24-hour: "07:00". Callers pass [rememberUse24Hour]. */
    fun time(time: LocalTime, use24Hour: Boolean): String {
        if (use24Hour) return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
        val hour = (time.hour + 11) % 12 + 1
        val marker = if (time.hour < 12) "am" else "pm"
        return if (time.minute == 0) "$hour $marker"
        else String.format(Locale.ROOT, "%d:%02d %s", hour, time.minute, marker)
    }

    /** An instant on a shift's timeline at its Melbourne wall-clock time, as [time] prints it. */
    fun time(at: Instant, use24Hour: Boolean): String = time(ShiftRules.local(at).toLocalTime(), use24Hour)

    /** "Thu 8 Jan". */
    fun dayDate(date: LocalDate): String = DAY_DATE.format(date)

    /** "Thu 8 Jan 2026", where the year would otherwise be ambiguous. */
    fun fullDate(date: LocalDate): String = FULL_DATE.format(date)

    /**
     * Whole degrees from [HeatRules.wholeDegrees], rounded half up: 31.5 is 32°, 33.4 is 33°, -0.4 is 0°. Levels and
     * the crew rule are judged on this same number, so a printed 32° is always Hot. Never round any other way, and never truncate.
     * Like [HeatRules.wholeDegrees], it throws for a non-finite value; callers pass validated readings.
     */
    fun temperature(c: Double): String = "${HeatRules.wholeDegrees(c)}°"

    /** "45 min", "7 h", "7 h 23 min". Never seconds, never a decimal hour. */
    fun duration(minutes: Int): String {
        require(minutes >= 0) { "Duration must not be negative: $minutes" }
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** An elapsed clock for the break card: "04:12", and "1:04:12" from one hour. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = s % 3600 / 60
        val rest = s % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest) else String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
    }
}

/** Whether the phone is set to 24-hour time; every displayed clock time follows it. */
@Composable
fun rememberUse24Hour(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var use24Hour by remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) use24Hour = DateFormat.is24HourFormat(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return use24Hour
}
