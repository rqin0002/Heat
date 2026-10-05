package heatshield.ui

import heatshield.data.MelbourneZone
import heatshield.data.UserPreferences
import heatshield.domain.HeatRules
import java.time.LocalDate

/** First-run routing and Profile input rules. Pure Kotlin, so they run as JVM tests. */
object SetupRules {
    const val WELCOME_ROUTE = "welcome"
    const val SETUP_ROUTE = "setup"
    const val TODAY_ROUTE = "today"
    const val MIN_SHIFT_MINUTES = 60

    fun needsSetup(preferences: UserPreferences?): Boolean = preferences == null || !preferences.setupDone

    /** Signed out goes to Welcome; an owner without finished setup goes to setup; everyone else opens Today. */
    fun startRoute(ownerId: String?, needsSetup: Boolean): String = when {
        ownerId == null -> WELCOME_ROUTE
        needsSetup -> SETUP_ROUTE
        else -> TODAY_ROUTE
    }

    /** Same rule as the DAO: finish at least an hour after start, both inside one day. */
    fun hoursValid(startMinute: Int, endMinute: Int): Boolean =
        startMinute in 0..1439 && endMinute in (startMinute + MIN_SHIFT_MINUTES)..1439

    /** A custom hot-day rule in °C, or null when blank, not a number, not whole, or outside 25–50 ([HeatRules.isValidTrigger]). */
    fun parseTrigger(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { HeatRules.isValidTrigger(it) }

    /** The rule as typed text. Stored rules are whole (the DAO checks [HeatRules.isValidTrigger]), so 35.0 reads "35". */
    fun formatTrigger(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /**
     * "Back from leave" for the next 7 days: [today] and the six days after it. The value is the local midnight that
     * starts day +7, an exclusive end, because the reasoner counts it while it is after the start of today.
     */
    fun acclimatisingUntil(today: LocalDate): Long =
        today.plusDays(HeatRules.ACCLIMATISING_DAYS).atStartOfDay(MelbourneZone).toInstant().toEpochMilli()
}
