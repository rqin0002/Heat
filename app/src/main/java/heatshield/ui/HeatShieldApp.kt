@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import heatshield.R
import heatshield.background.UnwellRequest
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.OpenShift
import heatshield.data.MelbourneZone
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class MainDestination(val route: String, @param:StringRes val title: Int, val icon: ImageVector)

val mainDestinations = listOf(
    MainDestination("today", R.string.tab_today, Icons.Outlined.WbSunny),
    MainDestination("records", R.string.tab_log, Icons.AutoMirrored.Outlined.ListAlt),
    MainDestination("trends", R.string.tab_trends, Icons.Outlined.BarChart),
    MainDestination("profile", R.string.tab_profile, Icons.Outlined.PersonOutline)
)

@Composable
fun HeatShieldApp(model: HeatShieldViewModel = viewModel(), startRoute: String? = null, onStartRouteHandled: () -> Unit = {},
                  unwell: UnwellRequest? = null, onUnwellHandled: () -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model.ownerId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.setVisible(true)
            if (event == Lifecycle.Event.ON_STOP) model.setVisible(false)
        }
        lifecycle.addObserver(observer)
        model.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); model.setVisible(false) }
    }
    key(model.ownerId) { AppNavigation(model, startRoute, onStartRouteHandled, unwell, onUnwellHandled) }
}

@Composable
private fun AppNavigation(model: HeatShieldViewModel, startRoute: String?, onStartRouteHandled: () -> Unit,
                          unwell: UnwellRequest?, onUnwellHandled: () -> Unit) {
    var comparingPlan by rememberSaveable { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: SetupRules.startRoute(model.ownerId, model.needsSetup)
    androidx.activity.compose.BackHandler(enabled = model.busy && route != FirstAid.ROUTE) { }
    val graphReady = entry != null
    // Every Start shift (Today's, the break sheet's and the hot-day nudge's) asks the owner about alerts the first time.
    val startShiftRoute = { if (model.alertsAsked()) "start-shift" else "alerts-intro" }
    val openStartShift: () -> Unit = { nav.navigate(startShiftRoute()) { launchSingleTop = true } }
    LaunchedEffect(startRoute, graphReady, model.ownerId) {
        if (!graphReady || startRoute == null) return@LaunchedEffect
        // First aid is for anyone holding the phone: the shortcut opens it on Welcome too. Every other deep link is an owner's and waits for sign-in.
        if (startRoute != FirstAid.ROUTE && model.ownerId == null) return@LaunchedEffect
        // Over Today, never over another screen: Back from a deep link returns to Today, not to a sheet left open behind it.
        // Signed out, Today is not on the back stack and Navigation skips the pop, so First aid stays over Welcome.
        nav.navigate(if (startRoute == "start-shift") startShiftRoute() else startRoute) { popUpTo("today"); launchSingleTop = true }
        onStartRouteHandled()
    }
    // The notification's Unwell: first aid is open from the route above; the break starts once the ViewModel knows the open shift
    // (shiftLoaded) and it is the shift the notification named. startUnwellBreak returns false until then, so the request waits
    // across a cold start instead of being consumed as a no-op; the effect re-runs as the shift loads and as busy clears.
    LaunchedEffect(unwell, model.shiftLoaded, model.busy, model.ownerId) {
        if (unwell != null && model.startUnwellBreak(unwell)) onUnwellHandled()
    }
    val signedOut = model.ownerId == null
    val parent = when {
        // Fix times opened from End shift's summary stays under Today; from Log it belongs to Log.
        route.startsWith("fix") && nav.previousBackStackEntry?.destination?.route == "summary/{id}" -> "today"
        route.startsWith("detail") || route.startsWith("fix") || route == "search" || route == "add-shift" -> "records"
        route.startsWith("summary") || route == "start-shift" || route == "plan" || route == "break" || route == "preview" || route == "alerts-intro" -> "today"
        else -> route
    }
    val title = stringResource(when (route) {
        "login" -> R.string.screen_title_sign_in
        "signup" -> R.string.screen_title_signup
        "today" -> R.string.tab_today
        "plan" -> if (comparingPlan) R.string.screen_title_compare else R.string.screen_title_plan
        "records" -> R.string.tab_log
        "search" -> R.string.screen_title_search
        "detail/{id}" -> R.string.screen_title_shift
        "add-shift" -> R.string.screen_title_add_past
        "trends" -> R.string.tab_trends
        "profile" -> R.string.tab_profile
        "break" -> R.string.screen_title_break
        "start-shift" -> R.string.screen_title_start_shift
        "alerts-intro" -> R.string.screen_title_alerts
        "summary/{id}" -> R.string.screen_title_summary
        "fix/{id}" -> R.string.screen_title_fix_times
        "setup" -> R.string.setup_title
        "preview" -> R.string.preview_title
        FirstAid.ROUTE -> R.string.first_aid_top_title
        else -> R.string.screen_title_app
    })
    val canGoBack = entry != null && nav.previousBackStackEntry != null && mainDestinations.none { it.route == route }
    val pageWidth = when (route) {
        "login", "signup" -> 480.dp
        "today", "records", "search", "trends" -> 840.dp
        "break", "start-shift", "fix/{id}", "add-shift", "alerts-intro" -> 640.dp
        "summary/{id}" -> 720.dp
        else -> 720.dp
    }

    Scaffold(
        containerColor = Paper,
        topBar = {
            if (route != "welcome") Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TopAppBar(
                    modifier = Modifier.widthIn(max = pageWidth).fillMaxWidth(),
                    title = {
                        Text(
                            title,
                            Modifier.testTag("app_title").semantics { heading() },
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    expandedHeight = 64.dp * fontScale.coerceIn(1f, 2f),
                    actions = {
                        if (route == "break" || mainDestinations.any { it.route == route }) TextButton(
                            onClick = { nav.navigate(FirstAid.ROUTE) { launchSingleTop = true } },
                            colors = ButtonDefaults.textButtonColors(contentColor = HeatShieldTheme.colors.emergency)
                        ) {
                            Icon(Icons.Outlined.MedicalServices, null)
                            Spacer(Modifier.width(UiSpace.tiny))
                            Text(stringResource(R.string.first_aid_top_action))
                        }
                    },
                    navigationIcon = navigationIcon@{
                        if (route == FirstAid.ROUTE) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.first_aid_back))
                            }
                            return@navigationIcon
                        }
                        if (canGoBack) IconButton(enabled = !model.busy, onClick = {
                            if (route == "plan" && comparingPlan) comparingPlan = false else nav.popBackStack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                stringResource(R.string.top_bar_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
                )
            }
        },
        bottomBar = {
            if (!signedOut && route !in setOf("welcome", "setup", "login", "signup", "preview", FirstAid.ROUTE)) Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                HorizontalDivider(color = Line, thickness = 1.dp)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NavigationBar(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                            .heightIn(min = if (fontScale > 1.3f) 104.dp else 80.dp),
                        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp
                    ) {
                        mainDestinations.forEach { dest ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${dest.route}"),
                                selected = parent == dest.route,
                                enabled = !model.busy,
                                onClick = {
                                    if (dest.route == parent) {
                                        if (!nav.popBackStack(dest.route, inclusive = false)) nav.navigate(dest.route) {
                                            popUpTo("today")
                                            launchSingleTop = true
                                        }
                                    } else nav.navigate(dest.route) {
                                        popUpTo("today") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(dest.icon, null, Modifier.size(22.dp)) },
                                label = { Text(stringResource(dest.title), style = MaterialTheme.typography.labelMedium) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = HeatShieldTheme.colors.onActionContainer,
                                    selectedTextColor = PrimaryAction,
                                    indicatorColor = SelectedSurface,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            model.message?.takeIf { route != "login" }?.let { shown ->
                val colors = HeatShieldTheme.colors
                Surface(color = if (shown.isError) colors.attentionSurface else colors.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("app_message"), verticalAlignment = Alignment.CenterVertically) {
                        // An error is a neutral notice: the icon, not a warm colour, marks it (palette spec).
                        if (shown.isError) Icon(Icons.Outlined.ErrorOutline, null, Modifier.padding(end = UiSpace.small).size(20.dp), tint = colors.attention)
                        Text(shown.text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (shown.isError) colors.attention else colors.ink)
                        TextButton(onClick = model::dismissMessage) { Text(stringResource(R.string.app_message_dismiss)) }
                    }
                }
            }
            if (model.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val start = rememberSaveable { SetupRules.startRoute(model.ownerId, model.needsSetup) }
            NavHost(nav, startDestination = start) {
                composable("welcome") { WelcomeScreen(onGetStarted = model::enterLocal,
                    onSignIn = { model.dismissMessage(); nav.navigate("login") },
                    onCreateAccount = { model.dismissMessage(); nav.navigate("signup") }, busy = model.busy) }
                composable("setup") {
                    SetupScreen(model.sites.map { it.name }, model.preferences, model.busy, upgrading = model.upgradingOwner) { site, task, startMinute, endMinute, smoko, lunch, acclimatising ->
                        model.completeSetup(site, task, startMinute, endMinute, smoko, lunch, acclimatising) {
                            nav.navigate("today") { popUpTo("setup") { inclusive = true } }
                        }
                    }
                }
                composable(FirstAid.ROUTE) { FirstAidScreen(onDial = rememberDialer()) }
                composable("preview") {
                    PreviewScreen(ContextReasoner.profileOf(model.preferences, LocalDate.now(MelbourneZone)), onExit = { nav.popBackStack() },
                        realShiftOpen = model.openShift != null, breakRule = BreakRule.fromStored(model.preferences.breakRule),
                        smokoMinute = model.preferences.smokoMinute, lunchMinute = model.preferences.lunchMinute,
                        saving = model.busy,
                        onSavePreviewShift = { result, saved -> model.savePreviewShift(result) { saved(); nav.popBackStack() } })
                }
                composable("login") { LoginScreen(
                    onLogin = { email, password -> model.authenticate(email, password, false) },
                    onSignUp = { model.dismissMessage(); nav.navigate("signup") },
                    onReset = model::resetPassword, configured = model.firebaseConfigured, busy = model.busy, message = model.message) }
                composable("signup") { SignUpScreen(
                    onCreated = { email, password -> model.authenticate(email, password, true) },
                    onLogin = { nav.navigate("login") { popUpTo("signup") { inclusive = true }; launchSingleTop = true } }, configured = model.firebaseConfigured, busy = model.busy) }
                composable("today") { today ->
                    TodayScreen(model,
                        onOpenPreview = { nav.navigate("preview") },
                        onOpenPlan = { comparingPlan = false; nav.navigate("plan") },
                        onOpenStartShift = openStartShift,
                        onOpenBreak = { nav.navigate("break") },
                        // End shift's save can finish after the worker has opened First aid, which stays available while
                        // it saves. The summary opens only while this Today is still the screen on top; otherwise the
                        // screen they chose stays, and the saved shift is in the Log.
                        onOpenSummary = { id -> if (nav.currentBackStackEntry?.id == today.id) nav.navigate("summary/$id") },
                        onOpenFirstAid = { nav.navigate(FirstAid.ROUTE) { launchSingleTop = true } })
                }
                composable("alerts-intro") {
                    val toStartShift = { nav.navigate("start-shift") { popUpTo("alerts-intro") { inclusive = true }; launchSingleTop = true } }
                    // A refusal says where alerts can be turned on later, and turns them off: an owner upgraded from A2 with the
                    // plan reminder on would otherwise keep break alerts after "Not now".
                    AlertsIntroScreen(busy = model.busy,
                        onAllow = { granted ->
                            model.markAlertsAsked(); model.setAlerts(granted)
                            if (!granted) model.notice(R.string.alerts_intro_denied)
                            toStartShift()
                        },
                        onNotNow = { model.markAlertsAsked(); model.setAlerts(false); model.notice(R.string.alerts_intro_denied); toStartShift() })
                }
                composable("start-shift") {
                    StartShiftScreen(
                        sites = model.sites.map { it.name },
                        // After today's usual finish the pre-filled finish is the usual length on, not tomorrow's.
                        initial = startShiftInitial(model.context, model.now),
                        nowMillis = model.now, saving = model.busy, breakRule = model.breakRule,
                        onStart = { input -> model.startShift(input) { nav.popBackStack() } }
                    )
                }
                composable("summary/{id}") { screen ->
                    val id = screen.arguments?.getString("id")?.toIntOrNull() ?: 0
                    SavedShift(model, id) { shown ->
                        ShiftSummaryScreen(shown.shift, shown.events, model.hoursFor(shown.shift), model.preferences.hotDayTrigger,
                            model.preferences.smokoMinute, model.preferences.lunchMinute,
                            onFixTimes = { nav.navigate("fix/$id") }, onDone = { nav.popBackStack("today", inclusive = false) })
                    }
                }
                composable("fix/{id}") { screen ->
                    val id = screen.arguments?.getString("id")?.toIntOrNull() ?: 0
                    SavedShift(model, id) { shown ->
                        FixTimesScreen(shown.shift, shown.events, model.preferences.smokoMinute, model.preferences.lunchMinute, model.now, model.busy,
                            heatRecalculated = model.recalculationHoursFor(shown.shift).isNotEmpty()) { edit -> model.fixTimes(id, edit) { nav.popBackStack() } }
                    }
                }
                composable("plan") {
                    ShiftPlanScreen(
                        acceptedWindow = model.plannedWindow, initialContext = model.plannedContext,
                        forecast = model.forecast, refreshing = model.refreshing, sites = model.sites.map { it.name },
                        onRefresh = model::previewForecast, comparing = comparingPlan, onClearPlan = model::cancelPlan,
                        onStepChange = { comparingPlan = it }, onReturnToToday = { nav.popBackStack() },
                        onAccept = { window, context -> model.savePlan(window, context) { nav.popBackStack() } })
                }
                composable("break") { BreakScreen(model, onReturn = { nav.popBackStack() }, onStartShift = openStartShift,
                    onOpenFirstAid = { nav.navigate(FirstAid.ROUTE) { launchSingleTop = true } }) }
                composable("records") {
                    RecordsScreen(
                        model.sessions,
                        onDetail = { nav.navigate("detail/$it") },
                        onAdd = { nav.navigate("add-shift") },
                        onSearch = { nav.navigate("search") })
                }
                composable("search") { SearchScreen(model.sessions) { nav.navigate("detail/$it") } }
                composable("detail/{id}") { detail ->
                    val id = detail.arguments?.getString("id")?.toIntOrNull() ?: 0
                    val session = model.sessions.find { it.id == id }
                    // The row comes from the Log's list; its events from Room, live.
                    val withEvents by remember(model, id) { model.observeShift(id) }.collectAsStateWithLifecycle(null)
                    if (session != null) SessionDetailScreen(session, withEvents?.events.orEmpty(),
                        onFixTimes = { nav.navigate("fix/${session.id}") },
                        onDelete = { model.deleteRecord(session.id) {
                            nav.navigate("records") { popUpTo("records") { inclusive = true }; launchSingleTop = true }
                        } })
                    else PageColumn {
                        Text(stringResource(R.string.log_entry_gone))
                        PrimaryButton(stringResource(R.string.log_back_to_log)) { nav.navigate("records") }
                    }
                }
                composable("add-shift") {
                    AddPastShiftScreen(model.sites.map { it.name }, model.preferences.site, model.now, model.busy) { draft ->
                        model.addPastShift(draft) { nav.popBackStack() }
                    }
                }
                composable("trends") { TrendsScreen(model.sessions) }
                composable("profile") { ProfileScreen(model,
                    onOpenPreview = { nav.navigate("preview") },
                    onSignIn = { model.dismissMessage(); nav.navigate("login") },
                    onCreateAccount = { model.dismissMessage(); nav.navigate("signup") }) }
            }
        }
    }
}

/** One Room read of a saved shift; [shift] null means it is no longer in the log. */
private class LoadedShift(val shift: OpenShift?)

/**
 * A saved shift with its events, live from Room, for the summary and Fix times. Nothing shows until the first read
 * lands, so End shift never flashes "no longer in your log" over the row it has just written.
 */
@Composable
private fun SavedShift(model: HeatShieldViewModel, id: Int, content: @Composable (OpenShift) -> Unit) {
    val loaded by remember(model, id) { model.observeShift(id).map { LoadedShift(it) } }.collectAsStateWithLifecycle(null)
    val shown = loaded ?: return
    val shift = shown.shift
    if (shift == null) PageColumn { Text(stringResource(R.string.log_entry_gone)) } else content(shift)
}
