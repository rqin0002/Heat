package heatshield.ui

import android.app.Application
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import heatshield.background.AlertCoordinator
import heatshield.background.AlertStatus
import heatshield.background.ForecastWorker
import heatshield.background.UnwellRequest
import heatshield.data.*
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.Effort
import heatshield.domain.HourHeat
import heatshield.domain.OpenAnswerKind
import heatshield.domain.OpenShift
import heatshield.domain.ScheduledKind
import heatshield.domain.ShiftRules
import heatshield.domain.TodayHeat
import heatshield.domain.WorkTasks
import heatshield.R
import heatshield.withAppLocale
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class UiMessage(val text: String, val isError: Boolean)

/** Screen state and user actions; storage, network and reasoning live outside Compose. */
class HeatShieldViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HeatShieldDatabase.get(application)
    private val dao = database.dao()
    private val weather = WeatherRepository(application)
    private val auth = AuthRepository(application)
    /** M3: the one owner of alarms and notifications. The ViewModel only asks it to look again after a change. */
    private val alerts = AlertCoordinator(application)
    private val device = application.getSharedPreferences("heatshield_session", 0)
    private val shiftClock = AndroidShiftClock(application)
    private val shifts = ShiftRepository(database, shiftClock)
    private val bootNow by lazy { shiftClock.bootCount() }   // cannot change while this process lives
    private var shiftJob: Job? = null
    private var ownerJob: Job? = null
    /** The forecast on show ([forecast]): the owner's park, or another park Plan is comparing ([previewForecast]). */
    private var forecastJob: Job? = null
    /**
     * The owner's park, observed on its own ([ownerForecast]): the open shift's park while one runs, else the plan's or
     * the usual park. [previewForecast] never touches it, so Plan's look at another park and leaving Today never stop the
     * shift's forecast. M3's alert reconcile runs from this collector too.
     */
    private var ownerForecastJob: Job? = null
    private var tickerJob: Job? = null
    private var ownerGeneration = 0
    private val preferenceWrites = Mutex()

    val firebaseConfigured get() = auth.configured
    var ownerId by mutableStateOf<String?>(null); private set
    var email by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<UiMessage?>(null); private set
    var sessions by mutableStateOf<List<WorkSession>>(emptyList()); private set
    var preferences by mutableStateOf(UserPreferences(ownerId = "local")); private set
    var needsSetup by mutableStateOf(false); private set
    /**
     * True only for an owner whose stored row predates setup: the v1→v2 migration keeps the row with setupDone false.
     * A brand-new owner has no row at all, so setup never tells them about earlier settings or a log they don't have.
     */
    var upgradingOwner by mutableStateOf(false); private set
    var plan by mutableStateOf<ShiftPlan?>(null); private set
    /** The forecast on show: Today's park, or the park Plan is comparing. Never the source of a running shift's hours. */
    var forecast by mutableStateOf<ForecastSnapshot?>(null); private set
    /** The owner's park's cached forecast, from [ownerForecastJob]: a running shift is timed, summarised and auto-ended from it. */
    var ownerForecast by mutableStateOf<ForecastSnapshot?>(null); private set
    /** Saved-shift pages read their own park before showing content; a null value is a confirmed missing cache. */
    private var savedShiftForecasts by mutableStateOf<Map<String, ForecastSnapshot?>>(emptyMap())
    var refreshing by mutableStateOf(false); private set
    var sites by mutableStateOf(WorkSite.defaults); private set
    var catalogueMessage by mutableStateOf(string(R.string.notice_parks_bundled)); private set
    var now by mutableStateOf(System.currentTimeMillis()); private set
    var elapsedNow by mutableStateOf(SystemClock.elapsedRealtime()); private set
    /** The owner's open shift with its events, straight from Room; null off shift. */
    var openShift by mutableStateOf<OpenShift?>(null); private set
    /** Profile › Alerts rows and Today's banner; refreshed on start, after every reconcile and on a preference change. */
    var alertStatus by mutableStateOf(AlertStatus(alertsOn = false, notificationsAllowed = false, exactAllowed = false, breakChannelSilent = false, asked = false)); private set
    /** The gardener swiped the lock-screen timer away for this shift; Today offers to show it again. */
    var showTimerAgain by mutableStateOf(false); private set
    /** The owner's open shift has been read from Room at least once, so a cold-start deep link can tell "no shift" from "not loaded yet". */
    var shiftLoaded by mutableStateOf(false); private set

    fun refreshAlertStatus() {
        alertStatus = alerts.status(preferences.reminders)
        // Only when the timer could show again: alerts on and allowed. Otherwise tapping it would bring nothing back.
        showTimerAgain = alertStatus.deliverable && openShift?.let { alerts.ongoingDismissed(it.shift.id) } == true
    }

    /** This owner has answered the alerts intro (per owner, as the switch is). Read from storage, so a cold-start deep link knows at once. */
    fun alertsAsked(): Boolean = ownerId?.let(alerts::asked) == true
    val breakRule: BreakRule get() = BreakRule.fromStored(preferences.breakRule)
    /**
     * Today's work. The open shift's park, work, switches and hours come first, then a plan for today, then the usual
     * day, so the forecast subscription, ForecastWorker and the Plan screen all follow the shift (spec §5.5).
     */
    val context: WorkContext get() {
        val today = Instant.ofEpochMilli(now).atZone(MELBOURNE).toLocalDate()
        val shift = openShift?.shift
        if (shift == null) plan?.context?.takeIf { it.date == today }?.let { return it }
        val profile = ContextReasoner.effectiveProfile(preferences, null, today, shift)
        val effort = when {
            shift == null -> ContextReasoner.usualEffort(preferences)
            shift.heavyWork -> Effort.HEAVY
            else -> WorkTasks.effortFor(shift.title).takeIf { it != Effort.HEAVY } ?: Effort.MODERATE
        }
        val clothing = when {
            shift == null -> preferences.clothing
            shift.heavyClothing -> "Heavy protective clothing"
            else -> "Standard workwear"
        }
        return WorkContext(work = profile.taskName, effort = effort.label, clothing = clothing, site = profile.site,
            dayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            startHour = profile.shiftStartMinute / 60, startMinute = profile.shiftStartMinute % 60, endMinute = profile.shiftEndMinute)
    }
    /** A plan whose day has passed no longer applies: Plan then opens on today's usual day (spec §5.5). */
    private val currentPlan get() = plan?.takeIf { !it.context.date.isBefore(LocalDate.now(MELBOURNE)) }
    val plannedContext get() = currentPlan?.context ?: context
    val plannedWindow get() = currentPlan?.window.orEmpty()
    /**
     * Today's heat from Room state and the 1 s ticker; Compose re-reads it as those change. Today's work comes from
     * one source: the open shift's park, work and hours, otherwise a plan saved for today, otherwise the usual day.
     * Today and the break sheet both read this, so they judge the same work.
     */
    val todayHeat: TodayHeat get() {
        val clock = Clock.fixed(Instant.ofEpochMilli(now), MelbourneZone)
        val profile = ContextReasoner.effectiveProfile(preferences, plan, LocalDate.now(clock), openShift?.shift)
        return ContextReasoner.todayHeat(forecastFor(profile.site), profile, clock)
    }

    /** The cached forecast of [site]: the owner's park's own collector first, else the forecast on show; null when neither is that park's. */
    private fun forecastFor(site: String): ForecastSnapshot? =
        ownerForecast?.takeIf { it.site == site } ?: forecast?.takeIf { it.site == site }

    /** Now on the open shift's timeline, from the ticked states so Compose re-reads it every second. */
    private fun shiftNow(shift: WorkSession): Instant = Instant.ofEpochMilli(
        requireNotNull(shift.startedAt) + ShiftRules.recoveredElapsedMillis(requireNotNull(shift.startedAt), shift.startedElapsed, shift.bootCount, now, elapsedNow, bootNow))

    /**
     * The Today state machine input, derived each tick. The shift reads every hour of its own park's forecast
     * ([ownerForecast]), past its planned finish and across midnight; only a current forecast times breaks (Global
     * Constraint 10).
     */
    val shiftState: ShiftUiState get() {
        val open = openShift ?: return ShiftUiState.Off
        val evaluation = ShiftRules.evaluate(open, ContextReasoner.shiftTimingHours(forecastFor(open.shift.site), shiftProfile(open.shift), now), breakRule,
            preferences.smokoMinute, preferences.lunchMinute, shiftNow(open.shift))
        return evaluation.openBreak?.let { ShiftUiState.OnBreak(open.shift, evaluation, it) } ?: ShiftUiState.On(open.shift, evaluation)
    }

    /** A shift's own park, work and switches over the worker's own rule and acclimatisation (ContextReasoner.effectiveProfile). */
    private fun shiftProfile(shift: WorkSession) = ContextReasoner.effectiveProfile(preferences, null,
        Instant.ofEpochMilli(now).atZone(MelbourneZone).toLocalDate(), shift.copy(endedAt = null))

    /**
     * Every hour of the cached forecast for [shift]'s park ([forecastFor]: the owner's park's own collector while the shift
     * runs), whatever its date or age (not a missing forecast), through the shift's profile: End shift, auto-end and Fix
     * times summarise from these. Saved pages can supply their separately read [snapshot]; null means no cache was
     * saved for that park. A row with no times has no shift to read.
     */
    private fun shiftHours(shift: WorkSession, snapshot: ForecastSnapshot? = forecastFor(shift.site)): List<HourHeat> =
        if (shift.startedAt == null) emptyList() else ContextReasoner.shiftHours(snapshot, shiftProfile(shift), now)

    /** Uses the completed cache read for a saved park, including a missing row; otherwise uses a currently observed park. */
    private fun savedForecastFor(site: String): ForecastSnapshot? =
        if (savedShiftForecasts.containsKey(site)) savedShiftForecasts[site] else forecastFor(site)

    /** The open shift's standing answer, as [shiftState]'s evaluation reads it (events only, so no forecast walk); null off shift. */
    val openAnswer: OpenAnswerKind? get() = openShift?.let { ShiftRules.openAnswer(it.events) }

    private var observedSite: String? = null
    private var ownerForecastSite: String? = null
    /**
     * The park whose cached forecast Room has delivered to [ownerForecast] since its collector started (null until then).
     * Auto-end waits for the open shift's park here, so a shift found overdue as the app reopens is summarised from its
     * cached forecast rather than from the empty state before the first read, whatever forecast is on show.
     */
    private var ownerForecastReadFor: String? = null
    private val foregroundRefresh by lazy {
        ForecastRefreshCoordinator(viewModelScope,
            refresh = { site -> weather.refresh(site); Unit },
            setRefreshing = { refreshing = it },
            onFailure = {
                message = UiMessage(string(R.string.notice_forecast_not_saved), isError = true)
            })
    }


    init {
        // Subscribed before the first owner's first reconcile: a forgotten shift the alert coordinator closes as the app
        // opens is announced here as when this ViewModel's own check closes it.
        viewModelScope.launch {
            AlertCoordinator.autoEnded.collect { ended -> if (ended.ownerId == ownerId) message = autoEndedNotice(ended.endedAt) }
        }
        switchOwner(auth.user.value?.id ?: if (device.getBoolean("local", false)) "local" else null)
        viewModelScope.launch {
            auth.user.collect { user ->
                email = user?.email
                val next = user?.id ?: if (device.getBoolean("local", false)) "local" else null
                if (next != ownerId) switchOwner(next)
            }
        }
        viewModelScope.launch {
            dao.observeSites().collect { cached ->
                sites = (cached + WorkSite.defaults).distinctBy { it.id }
                if (ownerId != null) resolveSite(context.site)?.let { ForecastWorker.enqueue(getApplication(), it) }
            }
        }
    }

    private fun switchOwner(next: String?) {
        ownerGeneration++
        ownerJob?.cancel(); forecastJob?.cancel(); ownerForecastJob?.cancel(); foregroundRefresh.cancel()
        observedSite = null; ownerForecastSite = null; ownerForecastReadFor = null; ownerForecast = null
        savedShiftForecasts = emptyMap()
        ownerId = next
        sessions = emptyList(); plan = null; forecast = null; message = null; busy = false
        catalogueMessage = string(R.string.notice_parks_bundled)
        preferences = UserPreferences(ownerId = next ?: "local")
        needsSetup = false; upgradingOwner = false; loading = next != null
        // Under the coordinator's owner lock: it waits for a reconcile inside its Room reads, then releases the previous
        // owner's alarms and notifications. Never cancelled, so a sign-out (which returns early below) always releases.
        val activated = viewModelScope.launch { alerts.activateOwner(next) }
        ForecastWorker.cancel(getApplication())
        shiftJob?.cancel(); openShift = null; shiftLoaded = false; showTimerAgain = false
        if (next == null) { setVisible(false); return }
        shiftJob = viewModelScope.launch {
            shifts.observeOpenShift(next).collect { open ->
                openShift = open
                shiftLoaded = true
                refreshAlertStatus()
                // A shift update follows its work park without taking over a separate Plan comparison.
                if (!loading) observeOwnerForecast(context.site)
                if (open != null) autoEndIfOverdue(next)
            }
        }
        ownerJob = viewModelScope.launch {
            combine(dao.observeSessions(next), dao.observePreferences(next), dao.observePlan(next)) { records, prefs, savedPlan ->
                Triple(records, prefs, savedPlan)
            }.collect { (records, prefs, savedPlan) ->
                // Today owns the open shift; the Log, search and Trends list finished shifts only.
                sessions = records.filter { !it.isOpen }
                preferences = prefs ?: UserPreferences(ownerId = next)
                upgradingOwner = prefs != null && !prefs.setupDone
                needsSetup = SetupRules.needsSetup(prefs)
                plan = savedPlan
                loading = false
                val siteName = context.site
                observeOwnerForecast(siteName)
                if (forecastJob?.isActive != true) previewForecast(siteName)
                // The new owner's first reconcile runs once it is the active owner; later emissions (a preference or
                // plan change) reconcile again, since the switch, the usual start and the break rule all move the plan.
                activated.join()
                alerts.reconcile(); refreshAlertStatus()
            }
        }
        loadSites()
    }

    /** Explicitly selects the work park for display, as Today's park switcher does; owner updates alone leave Plan alone. */
    private fun observeForecast(siteName: String) {
        observeOwnerForecast(siteName)
        if (forecastJob?.isActive == true && observedSite == siteName) return
        observedSite = siteName
        forecastJob?.cancel(); forecast = null
        forecastJob = viewModelScope.launch { weather.observe(siteName).collect { publishForecast(it) } }
        refreshForecast(siteName)
    }

    /**
     * Follows the current work park, apart from a comparison park, and asks for its refresh now. Its first reading
     * reconciles alerts, and that reconcile points background refresh at the same park: AlertCoordinator reads today's
     * work park itself, so the worker follows it with the app closed too.
     * Example: when midnight makes tomorrow's Royal Park plan apply, the next tick moves this collector from Birrarung
     * Marr to Royal Park. Further ticks at Royal Park reuse it; [previewForecast] never moves it.
     */
    private fun observeOwnerForecast(siteName: String) {
        if (ownerForecastJob?.isActive == true && ownerForecastSite == siteName) return
        ownerForecastSite = siteName
        ownerForecastJob?.cancel(); ownerForecast = null; ownerForecastReadFor = null
        ownerForecastJob = viewModelScope.launch { weather.observe(siteName).collect { publishOwnerForecast(it, siteName) } }
        refreshForecast(siteName)
    }

    /**
     * Room emits a refreshed row as soon as it lands, up to a second before the ticker next moves [now]. A fetch time
     * ahead of [now] can't be aged and reads as stale (ForecastSnapshot.freshness), so the clock catches up first.
     */
    private fun publishForecast(snapshot: ForecastSnapshot?) {
        now = System.currentTimeMillis()
        forecast = snapshot
    }

    private fun publishOwnerForecast(snapshot: ForecastSnapshot?, siteName: String) {
        now = System.currentTimeMillis()
        ownerForecast = snapshot
        // Room has answered for the owner's park, with a row or with none: an overdue shift there can now be summarised.
        ownerForecastReadFor = siteName
        if (openShift != null) ownerId?.let { owner -> viewModelScope.launch { autoEndIfOverdue(owner) } }
        // Every change of the owner's park's cache (this screen's own refresh, the worker's, a failed attempt) can move the
        // next break, or let a stale forecast time breaks again: the alarms follow at once. The reconcile reads its own
        // forecast from Room and is idempotent.
        if (ownerId != null) viewModelScope.launch { alerts.reconcile(); refreshAlertStatus() }
    }

    fun enterLocal() {
        if (busy) return
        auth.signOut()
        device.edit().putBoolean("local", true).apply()
        switchOwner("local")
    }

    fun authenticate(email: String, password: String, create: Boolean) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            val result = if (create) auth.signUp(email, password) else auth.signIn(email, password)
            result.fold(onSuccess = {
                device.edit().putBoolean("local", false).apply()
                this@HeatShieldViewModel.email = it.email
                if (ownerId != it.id) switchOwner(it.id)
            }, onFailure = { message = UiMessage(it.message ?: string(R.string.notice_sign_in_failed), isError = true) })
            busy = false
        }
    }

    fun resetPassword(email: String) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            auth.resetPassword(email).fold(
                onSuccess = { message = UiMessage(string(R.string.notice_reset_sent), isError = false) },
                onFailure = { message = UiMessage(it.message ?: string(R.string.notice_reset_failed), isError = true) })
            busy = false
        }
    }

    fun signOut() {
        if (busy) return
        device.edit().putBoolean("local", false).apply()
        auth.signOut()
        email = null
        switchOwner(null)
    }

    fun dismissMessage() { message = null }

    /** Saves one Profile change at once. The screen shows it immediately; writes run in order on the latest stored row. */
    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        val owner = ownerId ?: return
        val current = preferences.copy(ownerId = owner)
        val next = transform(current).copy(ownerId = owner)
        if (next == current) return
        val generation = ownerGeneration
        preferences = next
        viewModelScope.launch {
            preferenceWrites.withLock {
                try {
                    persistPreferences(owner, transform)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (generation == ownerGeneration) {
                        preferences = dao.getPreferences(owner) ?: UserPreferences(ownerId = owner)
                        message = UiMessage(error.message ?: string(R.string.notice_save_failed), isError = true)
                    }
                }
            }
        }
    }

    fun completeSetup(site: String, task: String, startMinute: Int, endMinute: Int, smokoMinute: Int, lunchMinute: Int,
                      acclimatising: Boolean, done: () -> Unit) =
        ownedAction(done) { owner ->
            val work = requireNotNull(WorkTasks.byName(task)) { string(R.string.setup_choose_work) }
            require(SetupRules.hoursValid(startMinute, endMinute)) { string(R.string.setup_hours_error) }
            require(smokoMinute in 0..1439 && lunchMinute in 0..1439) { string(R.string.notice_times_invalid) }
            val until = if (acclimatising) SetupRules.acclimatisingUntil(LocalDate.now(MELBOURNE)) else null
            persistPreferences(owner) {
                it.copy(site = site, task = work.name, effort = work.effort.label, shiftStartMinute = startMinute,
                    shiftEndMinute = endMinute, smokoMinute = smokoMinute, lunchMinute = lunchMinute,
                    acclimatisingUntil = until, setupDone = true)
            }
        }

    /**
     * Applies [transform] to the stored row, or to a new owner's defaults, in one transaction. Fields the
     * transform leaves alone (usual day, hot-day rule, setupDone) are never reset.
     *
     * Work-condition changes (effort, clothing, usual start/finish) invalidate a plan for today or later.
     * A site-only change invalidates only today's plan when it uses a different site from the new preference;
     * future plans survive. Compare conditions against the stored preferences, not against the plan: a plan may carry
     * its own task, effort, clothing and hours for today (Task 5's effectiveProfile), so a save that leaves the
     * usual day alone, such as the hot-day rule or the break-due alerts switch, keeps it. The alerts are reconciled
     * after the write: the switch, the usual start and the break rule all change what is armed.
     */
    private suspend fun persistPreferences(owner: String, transform: (UserPreferences) -> UserPreferences) {
        val today = LocalDate.now(MELBOURNE)
        database.withTransaction {
            val stored = dao.getPreferences(owner) ?: UserPreferences(ownerId = owner)
            val next = transform(stored).copy(ownerId = owner)
            require(resolveSite(next.site) != null) { string(R.string.notice_unknown_park) }
            val workConditionsChanged = next.effort != stored.effort || next.clothing != stored.clothing ||
                next.shiftStartMinute != stored.shiftStartMinute || next.shiftEndMinute != stored.shiftEndMinute
            val siteChanged = next.site != stored.site
            val savedPlan = dao.getPlan(owner)
            val invalidate = savedPlan?.let { saved ->
                (workConditionsChanged && !saved.context.date.isBefore(today)) ||
                    (siteChanged && saved.context.date == today && saved.site != next.site)
            } == true
            dao.savePreferences(next)
            if (invalidate) dao.deletePlan(owner)
        }
        alerts.reconcile()
    }

    /** The app's own English (AppLocale), so messages never follow the phone's language. */
    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().withAppLocale().resources.let {
            if (args.isEmpty()) it.getString(id) else it.getString(id, *args)
        }

    /**
     * Today's park switcher. The park becomes the usual park; only `site` changes in the stored row.
     * A plan for today at another park no longer applies, so it is deleted and the user is told.
     * While a shift runs, Today follows the shift's park and shows no switcher; a call then changes nothing.
     */
    fun selectSite(name: String) = ownedAction({}) { owner ->
        if (openShift != null) throw ShiftFailure(ShiftError.SHIFT_OPEN)
        val app = getApplication<Application>()
        require(resolveSite(name) != null) { app.getString(R.string.notice_unknown_park) }
        val replacedPlan = plan?.takeIf { it.site != name && it.context.date == LocalDate.now(MELBOURNE) }
        database.withTransaction {
            val current = dao.getPreferences(owner) ?: preferences
            dao.savePreferences(current.copy(ownerId = owner, site = name))
            if (replacedPlan != null) dao.deletePlan(owner)
        }
        if (replacedPlan != null && ownerId == owner) message = UiMessage(string(R.string.today_plan_cleared, replacedPlan.site), isError = false)
        // An open shift's park outranks the switcher: the forecast stays on the shift until it ends.
        if (ownerId == owner) observeForecast(openShift?.shift?.site ?: name)
    }

    fun deleteRecord(id: Int, done: () -> Unit) = ownedAction(done) { owner -> shifts.deleteShift(owner, id) }

    fun savePlan(window: String, selected: WorkContext, done: () -> Unit) = ownedAction(done) { owner ->
        val selectedWindow = ContextReasoner.compare(selected, forecast, System.currentTimeMillis()).find { it.label == window }
        require(selectedWindow != null) { string(R.string.notice_plan_changed) }
        val date = Instant.ofEpochMilli(selected.dayMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val reminderAt = date.atTime(selectedWindow.startHour, 0).atZone(MELBOURNE).toInstant().toEpochMilli()
        val next = ShiftPlan(owner, selected.work, selected.effort, selected.clothing,
            selected.dayMillis, selected.startHour, selected.startMinute, selected.site,
            window, reminderAt, maxOf(System.currentTimeMillis(), (plan?.revision ?: 0L) + 1), selected.endMinute)
        dao.savePlan(next)
    }

    fun cancelPlan() = ownedAction({}) { owner -> dao.deletePlan(owner) }

    // ---- The shift clock. Every write goes through ShiftRepository; Room pushes the result back into openShift. ----

    fun startShift(input: StartShiftInput, done: () -> Unit) = ownedAction(done) { owner -> shifts.startShift(owner, input) }

    fun startBreak(reason: String = BreakReason.SHADE, done: () -> Unit = {}) = ownedAction(done) { owner ->
        shifts.startBreak(owner, reason, eventContext())
    }

    /** [done] runs once the break is saved: the break sheet closes then, so it never asks for the break just ended. */
    fun endBreak(done: () -> Unit = {}) = ownedAction(done) { owner -> shifts.endBreak(owner) }

    fun cantStop(done: () -> Unit) = ownedAction(done) { owner -> shifts.cantStop(owner, eventContext()) }

    fun confirmScheduled(kind: ScheduledKind, taken: Boolean) = ownedAction({}) { owner -> shifts.confirmScheduled(owner, kind, taken) }

    fun setHeavyWork(on: Boolean) = ownedAction({}) { owner -> shifts.setHeavyWork(owner, on) }

    // ---- Alerts (M3). The coordinator owns alarms and notifications; the ViewModel only asks it to look again. ----

    /** Profile's Break-due alerts switch (`UserPreferences.reminders`); the write reconciles. */
    fun setAlerts(on: Boolean) { updatePreferences { it.copy(reminders = on) }; alertStatus = alertStatus.copy(alertsOn = on) }

    fun markAlertsAsked() { ownerId?.let(alerts::markAsked); refreshAlertStatus() }

    /** A short confirmation in the app's message bar, in the app's English. */
    fun notice(@StringRes id: Int) { message = UiMessage(string(id), isError = false) }

    /**
     * Profile › Alerts on every resume and after the notification permission dialog. The dialog only pauses the activity
     * (no ON_STOP/ON_START, so [setVisible] does not reconcile), and while notifications were blocked the plan armed no
     * break alarm and posted no lock-screen timer: a grant there must bring both back at once, not at the next forecast
     * refresh or app open. Reconcile is idempotent and leaves an unchanged timer alone; the rows show at once and again
     * after it.
     */
    fun refreshAlerts() {
        refreshAlertStatus()
        if (ownerId != null) viewModelScope.launch { alerts.reconcile(); refreshAlertStatus() }
    }

    fun showTimerAgain() {
        alerts.clearDismissed()
        viewModelScope.launch { alerts.reconcile(); refreshAlertStatus() }
    }

    /**
     * The notification's Unwell action (DeepLinks.ACTION_UNWELL): first aid is already opening; the clock stops with an
     * unwell break once [request] is known to name this owner's open shift. False means "not yet" (the open shift has not
     * loaded, or a write is running): the caller asks again, so a cold start never consumes the request as a no-op. True
     * means handled: a break started, or there was nothing to start (signed out, another owner, another shift, already on
     * a break).
     */
    fun startUnwellBreak(request: UnwellRequest): Boolean {
        if (ownerId == null) return true
        if (!shiftLoaded || busy) return false
        val open = openShift
        if (ownerId == request.ownerId && open != null && open.shift.id == request.shiftId && shiftState is ShiftUiState.On) startBreak(BreakReason.UNWELL)
        return true
    }

    /** Writes the log and hands the saved shift's id to the summary. */
    fun endShift(done: (Int) -> Unit) = ownedAction({}) { owner ->
        val id = shifts.endShift(owner, openShift?.shift?.let(::shiftHours).orEmpty())
        if (ownerId == owner) done(id)
    }

    /**
     * Corrects saved times using a fresh read of that park's cache, even after Today returns to the usual park.
     * Only today's shifts recalculate heat; for example, shortening today's Royal Park shift still uses Royal Park.
     */
    fun fixTimes(shiftId: Int, edit: ShiftTimesEdit, done: () -> Unit) = ownedAction(done) { owner ->
        val shift = dao.getSession(owner, shiftId)
        val today = Instant.ofEpochMilli(now).atZone(MelbourneZone).toLocalDate()
        val hours = if (shift != null && shift.date == today.format(ShiftRepository.RECORD_DATE))
            shiftHours(shift, weather.snapshot(shift.site)) else emptyList()
        shifts.editTimes(owner, shiftId, edit, hours)
    }

    fun addPastShift(draft: PastShiftDraft, done: () -> Unit) = ownedAction(done) { owner -> shifts.addPastShift(owner, draft) }

    /**
     * Saves the completed practice shift and acknowledges it only after storage succeeds. Preview keeps [result]
     * available until [done], so a rejected write or a busy action can be retried without replaying the practice.
     */
    fun savePreviewShift(result: PreviewShiftResult, done: () -> Unit = {}) = ownedAction(done) { owner ->
        shifts.savePreviewShift(owner, result)
        if (ownerId == owner) message = UiMessage(string(R.string.preview_shift_saved), isError = false)
    }

    /**
     * Observes a saved shift with its own park's forecast, waiting for that cache's first answer before showing the row.
     * Example: ending Royal Park work can move Today to Birrarung Marr without hiding Royal Park's saved heat.
     * A later forecast arrival or refresh updates the open page. Null means the shift is absent; a forecast read
     * still in progress emits nothing yet. Leaving the page cancels both subscriptions together.
     */
    fun observeShift(id: Int): Flow<OpenShift?> = ownerId?.let { owner ->
        val generation = ownerGeneration
        kotlinx.coroutines.flow.channelFlow {
            var parkJob: Job? = null
            shifts.observeShift(owner, id).collect { shown ->
                parkJob?.cancel()
                if (shown == null) {
                    if (generation == ownerGeneration) send(null)
                } else {
                    parkJob = launch {
                        weather.observe(shown.shift.site).collect { snapshot ->
                            if (generation == ownerGeneration) {
                                savedShiftForecasts = savedShiftForecasts + (shown.shift.site to snapshot)
                                send(shown)
                            }
                        }
                    }
                }
            }
        }
    } ?: flowOf(null)

    /** What the panel showed when an event was written: the current hour's level and heat, and the due time it answers. */
    private fun eventContext(): EventContext {
        val evaluation = when (val state = shiftState) {
            is ShiftUiState.On -> state.evaluation
            is ShiftUiState.OnBreak -> state.evaluation
            ShiftUiState.Off -> null
        }
        val due = (shiftState as? ShiftUiState.On)?.evaluation?.due?.dueAt
        return EventContext(level = evaluation?.current?.effective, heatC = evaluation?.current?.heat, dueAt = due)
    }

    /**
     * Forecast hours for a saved shift's summary ribbon and peak: the hours of its park's cached forecast that overlap
     * its stored times, through that shift's own profile, past the planned finish and across midnight.
     */
    fun hoursFor(shift: WorkSession): List<HourHeat> {
        val start = shift.startedAt ?: return emptyList()
        val end = shift.endedAt ?: return emptyList()
        return ShiftRules.hoursOverlapping(shiftHours(shift, savedForecastFor(shift.site)), ShiftRules.instant(start), ShiftRules.instant(end))
    }

    /**
     * The hours Fix times recomputes a shift's heat from: every hour of its park's cached forecast, so edited times get
     * levels too, for a shift dated today only. Empty otherwise, and the stored heat then stays as it is.
     */
    fun recalculationHoursFor(shift: WorkSession): List<HourHeat> {
        val today = Instant.ofEpochMilli(now).atZone(MelbourneZone).toLocalDate()
        return if (shift.date == today.format(ShiftRepository.RECORD_DATE)) shiftHours(shift, savedForecastFor(shift.site)) else emptyList()
    }

    /**
     * A forgotten End shift: two hours past its automatic end (the planned finish, or a later break or answer) the
     * repository ends it there. Checked once Room has delivered the open shift's park forecast, whenever that forecast
     * changes, and once a minute while visible; the checks can overlap as the app reopens, and the repository ends the
     * shift once, so whichever check ended it shows the notice. A failure becomes a message rather than escaping
     * viewModelScope. It reads the shift park's own collector ([ownerForecast]), whatever forecast is on show.
     */
    private suspend fun autoEndIfOverdue(owner: String) {
        val shift = openShift?.shift ?: return
        // Not before the owner's preferences and the shift park's cached forecast are read: the summary needs both.
        if (loading || ownerForecastReadFor != shift.site) return
        try {
            val endedAt = shifts.autoEndIfOverdue(owner, shiftHours(shift))
            if (endedAt != null && ownerId == owner) message = autoEndedNotice(endedAt)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (ownerId == owner) message = UiMessage(messageFor(error), isError = true)
        }
    }

    private fun autoEndedNotice(endedAt: Instant) = UiMessage(string(R.string.shift_auto_ended_notice,
        Formats.time(endedAt, DateFormat.is24HourFormat(getApplication()))), isError = false)

    fun refreshForecast(siteName: String = context.site) {
        if (ownerId == null) return
        resolveSite(siteName)?.let(foregroundRefresh::request)
    }

    /**
     * Shows [siteName]'s forecast: Today's park, or another park Plan is comparing. Only the forecast on show moves; the
     * owner's park, and so a running shift, keeps its own collector ([ownerForecastJob]).
     */
    fun previewForecast(siteName: String) {
        if (observedSite == siteName && forecastJob?.isActive == true) { refreshForecast(siteName); return }
        observedSite = siteName
        forecastJob?.cancel(); forecast = null
        forecastJob = viewModelScope.launch { weather.observe(siteName).collect { publishForecast(it) } }
        refreshForecast(siteName)
    }

    fun loadSites() {
        val owner = ownerId
        if (owner == null || owner == "local") return
        viewModelScope.launch {
            val result = SiteRepository(getApplication()).load()
            if (owner != ownerId) return@launch
            result.fold(onSuccess = {
                catalogueMessage = string(R.string.notice_parks_shared)
            }, onFailure = { catalogueMessage = string(R.string.notice_parks_fallback) })
        }
    }

    private fun resolveSite(name: String) = sites.find { it.name == name }

    /**
     * Runs the on-screen clock and keeps the shown work park current across midnight without a row write.
     * Each second rechecks the park, but an unchanged park retains its collector. Hidden, the clock stops; the alert
     * reconcile that every background refresh and alarm runs keeps the periodic worker on the new day's park.
     */
    fun setVisible(value: Boolean) {
        tickerJob?.cancel()
        if (!value || ownerId == null) return
        // Back in view: the permission rows may have changed in Android settings, and an alarm may have been lost meanwhile.
        viewModelScope.launch { alerts.reconcile(); refreshAlertStatus() }
        tickerJob = viewModelScope.launch {
            var ticks = 0
            while (true) {
                now = System.currentTimeMillis()
                elapsedNow = SystemClock.elapsedRealtime()
                if (!loading) observeOwnerForecast(context.site)
                // Auto-end once a minute while visible, with its notice.
                if (ticks++ % 60 == 0) ownerId?.let { owner -> autoEndIfOverdue(owner) }
                delay(1_000)
            }
        }
    }

    private fun ownedAction(done: () -> Unit, block: suspend (String) -> Unit) {
        val owner = ownerId ?: return
        if (busy) return
        val generation = ownerGeneration
        busy = true; message = null
        viewModelScope.launch {
            try {
                block(owner)
                // Every shift, plan and park write can move what is armed and shown; reconcile never throws.
                alerts.reconcile()
                if (generation == ownerGeneration) done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == ownerGeneration) message = UiMessage(messageFor(error), isError = true)
            } finally { if (generation == ownerGeneration) busy = false }
        }
    }

    private fun messageFor(error: Exception): String =
        if (error is ShiftFailure) string(error.error.messageRes) else error.message ?: string(R.string.notice_save_failed)

    override fun onCleared() { auth.close(); super.onCleared() }

    companion object {
        val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
        /** The log's stored date format; [ShiftRepository] owns it. */
        val RECORD_DATE: DateTimeFormatter get() = ShiftRepository.RECORD_DATE
    }
}

/** One sentence per repository failure. */
val ShiftError.messageRes: Int get() = when (this) {
    ShiftError.SHIFT_OPEN -> R.string.notice_shift_open
    ShiftError.NO_SHIFT -> R.string.notice_no_shift
    ShiftError.BREAK_OPEN -> R.string.notice_break_open
    ShiftError.NO_BREAK -> R.string.notice_no_break
    ShiftError.INVALID_TIMES -> R.string.notice_times_invalid
    ShiftError.IN_FUTURE -> R.string.notice_time_future
    ShiftError.TOO_LONG -> R.string.notice_shift_too_long
    ShiftError.SHIFT_GONE -> R.string.log_entry_gone
    ShiftError.OVERLAPS -> R.string.notice_shift_overlaps
}

internal class ForecastRefreshCoordinator(
    private val scope: CoroutineScope,
    private val refresh: suspend (WorkSite) -> Unit,
    private val setRefreshing: (Boolean) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private var job: Job? = null
    private var siteName: String? = null
    private var generation = 0L

    fun request(site: WorkSite) {
        if (siteName == site.name && job?.isActive == true) return
        val current = ++generation
        job?.cancel()
        siteName = site.name
        setRefreshing(true)
        job = scope.launch {
            try {
                refresh(site)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current == generation) onFailure(error)
            } finally {
                if (current == generation) {
                    siteName = null
                    setRefreshing(false)
                }
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        siteName = null
        setRefreshing(false)
    }
}
