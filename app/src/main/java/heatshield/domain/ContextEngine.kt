package heatshield.domain

import heatshield.data.ForecastSnapshot
import heatshield.data.MelbourneZone
import heatshield.data.SensorReading
import heatshield.data.SensorReplay
import heatshield.data.WorkContext
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

data class ContextDecision(val title: String, val explanation: String, val checkInDue: Boolean)
data class WorkWindow(val label: String, val summary: String, val score: Double, val startHour: Int, val endHour: Int)

/** Transparent demonstration rules, not clinical thresholds or a safe-work certification. */
object ContextEngine {
    fun evaluate(
        reading: SensorReading?,
        context: WorkContext,
        outdoorMinutes: Int,
        pauseConstraint: String,
        now: Long = System.currentTimeMillis()
    ): ContextDecision {
        if (!context.isValid || outdoorMinutes !in 0..1440) {
            return ContextDecision("Conditions unavailable", "A valid replay reading and work context are required. Follow your workplace heat procedure.", false)
        }
        // A recorded, unresolved response does not disappear when sensor playback stops.
        if (pauseConstraint.isNotBlank()) {
            return ContextDecision("Follow-up needed", "$pauseConstraint. Discuss a lighter task or cooler location with your supervisor; the follow-up remains open.", true)
        }
        if (reading == null || !reading.isValid) {
            return ContextDecision("Conditions unavailable", "A valid replay reading is required. Follow your workplace heat procedure.", false)
        }
        if (reading.emittedAt <= 0 || now < reading.emittedAt || now - reading.emittedAt >= SensorReplay.INTERVAL_MILLIS * 2) {
            return ContextDecision("Replay paused or delayed", "No recent replay sample has arrived. Resume playback or advance the sample before using its decision.", false)
        }
        if (context.site != "Birrarung Marr") {
            return ContextDecision("Replay site differs", "The historical sensor is at Birrarung Marr. Use the selected site's forecast for planning; replay decisions apply only to its source site.", false)
        }
        // Replay time is intentionally used here; live calendar time would mislabel historical samples.
        val minuteOfDay = OffsetDateTime.parse(reading.observedAtLocal).let { it.hour * 60 + it.minute }
        val shiftStart = context.startHour * 60 + context.startMinute
        if (minuteOfDay < shiftStart || minuteOfDay >= 15 * 60) {
            return ContextDecision("Outside replay shift", "This historical observation is outside your ${String.format(Locale.ENGLISH, "%02d:%02d", context.startHour, context.startMinute)}–15:00 shift. Replay it within shift hours to review the work context.", false)
        }
        val contextLoad = effortWeight(context.effort) + if (context.clothing == "Heavy protective clothing") 1.0 else 0.0
        val conditionLoad = when {
            reading.temperature >= 32 -> 3.0
            reading.temperature >= 28 -> 2.0
            reading.temperature >= 24 -> 1.0
            else -> 0.0
        } + if (reading.humidity >= 70 && reading.temperature >= 24) 1.0 else 0.0
        val elapsedLoad = if (outdoorMinutes >= 90) 2.0 else if (outdoorMinutes >= 45) 1.0 else 0.0
        val due = outdoorMinutes > 0 && conditionLoad + contextLoad + elapsedLoad >= 5
        val reason = String.format(Locale.ENGLISH, "Historical replay %.1f°C / %.0f%% RH, %s, %s and %d minutes outdoors.",
            reading.temperature, reading.humidity, context.effort.lowercase(), context.clothing.lowercase(), outdoorMinutes)
        return if (due) ContextDecision("Check-in due", "$reason Review access to a cooler pause using your workplace procedure.", true)
        else ContextDecision("Continue to review conditions", "$reason Keep reviewing your work and available breaks. This result does not establish safe exposure.", false)
    }

    fun compare(context: WorkContext, forecast: ForecastSnapshot?, now: Long = System.currentTimeMillis()): List<WorkWindow> {
        if (!context.isValid || forecast == null || forecast.site != context.site || !forecast.isFresh(now)) return emptyList()
        if (forecast.hours.any { !it.isValid } || forecast.hours.map { it.time }.distinct().size != forecast.hours.size) return emptyList()
        val date = context.date
        val nowLocal = Instant.ofEpochMilli(now).atZone(MelbourneZone).toLocalDateTime()
        val byTime = forecast.hours.associateBy { it.localTime }
        val shiftStartMinutes = context.startHour * 60 + context.startMinute
        return (0..13).mapNotNull { hour ->
            val start = date.atTime(hour, 0)
            if (hour * 60 < shiftStartMinutes || start.isBefore(nowLocal)) return@mapNotNull null
            val first = byTime[start] ?: return@mapNotNull null
            val second = byTime[start.plusHours(1)] ?: return@mapNotNull null
            val end = byTime[start.plusHours(2)] ?: return@mapNotNull null
            // These are point-in-time forecasts: require both boundaries and the intervening hour.
            val samples = listOf(first, second, end)
            val temp = samples.maxOf { it.temperature }
            val rh = samples.maxOf { it.humidity }
            val uv = samples.maxOf { it.uv }
            val load = effortWeight(context.effort) + if (context.clothing == "Heavy protective clothing") 1.0 else 0.0
            val score = samples.map { sample ->
                sample.temperature + (sample.humidity - 50).coerceAtLeast(0.0) * 0.06 +
                    sample.uv * 0.6 + (sample.temperature - 20).coerceAtLeast(0.0) * load * 0.15
            }.average()
            WorkWindow(String.format(Locale.ENGLISH, "%02d:00–%02d:00", hour, hour + 2),
                String.format(Locale.ENGLISH, "Up to %.1f°C · %.0f%% RH · UV %.1f · %s", temp, rh, uv, context.effort.lowercase()),
                score, hour, hour + 2)
        }.sortedWith(compareBy<WorkWindow> { it.score }.thenBy { it.startHour })
    }

    private fun effortWeight(effort: String): Double = when (effort) {
        "Heavy effort" -> 2.0
        "Moderate effort" -> 1.0
        else -> 0.0
    }
}
