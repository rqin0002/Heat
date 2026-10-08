// Creates a labelled demonstration shift through the real alarm path. This stays in the debug source set so
// the team's adb demo cannot add an entry point to release builds.
package heatshield.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import com.google.gson.Gson
import heatshield.background.AlertCoordinator
import heatshield.data.AndroidShiftClock
import heatshield.data.ForecastCache
import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.HeatShieldDatabase
import heatshield.data.MelbourneZone
import heatshield.data.ShiftError
import heatshield.data.ShiftFailure
import heatshield.data.ShiftRepository
import heatshield.data.StartShiftInput
import heatshield.data.UserPreferences
import heatshield.domain.AlarmKind
import heatshield.ui.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only (src/debug; never in a release manifest). Seeds the phone's active owner with a real shift that started
 * 44 minutes ago (note "Demo data") on a seeded 33° forecast fetched just now, then reconciles, so the real ongoing timer, the
 * real break-due alarm, Start break from the lock screen and `adb reboot` recovery can be shown on the test AVD. On an unbumped
 * profile away from smoko and lunch the break falls due about a minute later (Hot at 33°: 45 minutes); an acclimatising
 * profile (the one personal bump the seeded shift keeps: it starts with heavy work and heavy clothing off) makes it Very hot
 * (30 minutes), so the break is already overdue and the alert posts at once with "Due since"; a due inside 15 minutes before
 * an unanswered smoko or lunch waits for it and buzzes at its time. The toast reports which of these the reconcile actually
 * planned. Preview cannot show any of this (Global Constraint 7). Every write is labelled and said: the shift's note, the
 * forecast's stamp on Today ("Demo forecast (debug build)"), and the toast, which also says that break-due alerts were
 * turned on and the park's cached forecast replaced. Nothing is written when a shift is already running.
 *   adb shell am broadcast -a heatshield.debug.DEMO_DATA -n au.edu.monash.heatshield/heatshield.debug.DemoDataReceiver
 */
class DemoDataReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val message = try { seed(app) } catch (error: Exception) { "Demo data not seeded: ${error.message}" } finally { pending.finish() }
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun seed(app: Context): String {
        val owner = app.getSharedPreferences(AlertCoordinator.PREFS, Context.MODE_PRIVATE).getString(AlertCoordinator.KEY_OWNER, null)
            ?: return "Demo data not seeded: open HeatShield and choose Get started, or sign in, first."
        val database = HeatShieldDatabase.get(app)
        val dao = database.dao()
        val preferences = dao.getPreferences(owner)?.takeIf { it.setupDone } ?: return "Demo data not seeded: finish setup first."
        // Checked before anything is written: a refused seed changes neither the alerts switch nor the park's cached forecast.
        if (dao.getOpenShift(owner) != null) return "Demo data not seeded: a shift is already running. End it first."
        val shifts = ShiftRepository(database, AndroidShiftClock(app))
        val id = try {
            shifts.startShift(owner, demoShiftInput(preferences, Instant.now()))
        } catch (failure: ShiftFailure) {
            return "Demo data not seeded: " + when (failure.error) {
                ShiftError.OVERLAPS -> "the last 44 minutes overlap a shift in the log; delete the earlier \"Demo data\" entry."
                ShiftError.SHIFT_OPEN -> "a shift is already running."
                else -> "the shift could not start (${failure.error})."
            }
        }
        dao.updateSession(owner, requireNotNull(dao.getSession(owner, id)).copy(note = "Demo data"))
        dao.savePreferences(preferences.copy(reminders = true))
        // Yesterday to tomorrow at 33° air (Hot: 45 minutes of work, then a break), as the app's refresh keeps them, so a seed
        // just after midnight still has the hour the shift started in. FRESH until the next successful refresh replaces them,
        // and stamped as the demo's, never as Open-Meteo's.
        val wall = System.currentTimeMillis()
        val hours = (-1L..1L).flatMap { d -> (0..23).map { h -> LocalDate.now(MelbourneZone).plusDays(d).atTime(h, 0) } }
            .map { ForecastHour(it.toString(), 33.0, 10.0, 6.0, windSpeed = 8.0, uvClearSky = 6.0) }
        dao.saveForecast(ForecastCache(preferences.site, wall, Gson().toJson(hours), wall, "success", refreshedBy = ForecastSnapshot.REFRESHED_BY_DEMO))
        val plan = AlertCoordinator(app).reconcile()
        // Report what the coordinator planned, not what a 45-minute cadence would do: an acclimatising profile makes 33° Very hot
        // (30 minutes), so 44 minutes is already overdue and the alert posted just now with "Due since"; a due inside 15 minutes
        // before an unanswered smoko or lunch waits for it, and the alarm is at its time.
        val nextDue = plan?.alarms?.firstOrNull { it.kind == AlarmKind.BREAK_DUE }?.at
        val outcome = when {
            nextDue != null -> "Break due at ${Formats.time(nextDue, DateFormat.is24HourFormat(app))}."
            plan?.alarms?.any { it.kind == AlarmKind.BREAK_REPEAT } == true -> "The break was already due on this profile's cadence: the alert posted at once and reads Due since."
            else -> "No break alarm: no current forecast hour, smoko or lunch comes first, or notifications are blocked."
        }
        return "Break-due alerts turned on; ${preferences.site}'s forecast replaced by a demo 33° day until the next refresh. " +
            "Demo data: a real shift at ${preferences.site} for $owner, started 44 min ago. $outcome"
    }

    companion object { const val ACTION = "heatshield.debug.DEMO_DATA" }
}

/**
 * Supplies the saved park and task for the demo's backdated shift, with heavy work and clothing off.
 * Subtracts 44 elapsed minutes from [now] rounded down to the minute, then gives Melbourne clock times to the
 * repository for its normal validation. At 03:10 AEDT on 4 October 2026, the start is 01:26 AEST, before the clock jump.
 * The planned finish remains eight displayed hours after now; an ordinary-day 14:00:37 gives a 22:00 finish.
 */
internal fun demoShiftInput(preferences: UserPreferences, now: Instant): StartShiftInput {
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val localNow = LocalDateTime.ofInstant(minute, MelbourneZone)
    return StartShiftInput(preferences.site, preferences.task, heavyWork = false, heavyClothing = false,
        startedAt = LocalDateTime.ofInstant(minute.minus(44, ChronoUnit.MINUTES), MelbourneZone), plannedEnd = localNow.plusHours(8))
}
