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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*

data class MainDestination(val route: String, val title: String, val icon: ImageVector)

val mainDestinations = listOf(
    MainDestination("today", "Today", Icons.Outlined.WbSunny),
    MainDestination("records", "Log", Icons.AutoMirrored.Outlined.ListAlt),
    MainDestination("trends", "Trends", Icons.Outlined.BarChart),
    MainDestination("profile", "Profile", Icons.Outlined.PersonOutline)
)

@Composable
fun HeatShieldApp(model: HeatShieldViewModel = viewModel()) {
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
    key(model.ownerId) { AppNavigation(model) }
}

@Composable
private fun AppNavigation(model: HeatShieldViewModel) {
    androidx.activity.compose.BackHandler(enabled = model.busy) { }
    var comparingPlan by rememberSaveable { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "login"
    val signedOut = model.ownerId == null
    val parent = when {
        route.startsWith("detail") || route.startsWith("edit") || route == "search" -> "records"
        route == "plan" || route == "break" -> "today"
        else -> route
    }
    val title = when (route) {
        "login" -> "HeatShield"; "signup" -> "Create account"; "today" -> "Today"
        "plan" -> if (comparingPlan) "Compare windows" else "Shift plan"
        "records" -> "Work records"; "search" -> "Search records"
        "detail/{id}" -> "Session detail"; "edit/{id}" -> if (entry?.arguments?.getString("id") == "0") "Add record" else "Edit record"
        "trends" -> "Trends"; "profile" -> "Profile"; "break" -> "Break check-in"; else -> "HeatShield"
    }
    val isSecondary = !signedOut && mainDestinations.none { it.route == route }
    val pageWidth = when (route) {
        "login", "signup" -> 480.dp
        "today", "records", "search", "trends" -> 840.dp
        "break" -> 640.dp
        else -> 720.dp
    }

    val start = if (signedOut) "login" else "today"
    LaunchedEffect(model.loading, model.needsOnboarding) {
        if (!signedOut && !model.loading && model.needsOnboarding) nav.navigate("profile") { launchSingleTop = true }
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                    navigationIcon = {
                        if (isSecondary || route == "signup") IconButton(enabled = !model.busy, onClick = {
                            if (route == "plan" && comparingPlan) comparingPlan = false else nav.popBackStack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)
                )
            }
        },
        bottomBar = {
            if (!signedOut) Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
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
                                    nav.navigate(dest.route) {
                                        popUpTo("today")
                                        launchSingleTop = true
                                    }
                                },
                                icon = { Icon(dest.icon, null, Modifier.size(22.dp)) },
                                label = { Text(dest.title, style = MaterialTheme.typography.labelMedium) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PrimaryAction,
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
            if (model.message != null && route != "login") Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.message.orEmpty(), Modifier.weight(1f))
                    TextButton(onClick = model::dismissMessage) { Text("Dismiss") }
                }
            }
            if (model.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            NavHost(nav, startDestination = start) {
                composable("login") { LoginScreen(
                    onLogin = { email, password -> model.authenticate(email, password, false) },
                    onSignUp = { model.dismissMessage(); nav.navigate("signup") }, onLocal = model::enterLocal,
                    onReset = model::resetPassword, configured = model.firebaseConfigured, busy = model.busy, message = model.message) }
                composable("signup") { SignUpScreen(
                    onCreated = { email, password -> model.authenticate(email, password, true) },
                    onLogin = { nav.popBackStack() }, configured = model.firebaseConfigured, busy = model.busy) }
                composable("today") {
                    LaunchedEffect(model.context.site) { model.previewForecast(model.context.site) }
                    TodayScreen(model,
                        { comparingPlan = false; nav.navigate("plan") },
                        { nav.navigate("break") },
                        { nav.navigate(model.activeSession?.let { "detail/${it.id}" } ?: "edit/0") })
                }
                composable("plan") {
                    ShiftPlanScreen(
                        acceptedWindow = model.plan?.window.orEmpty(), initialContext = model.plannedContext,
                        forecast = model.forecast, refreshing = model.refreshing, sites = model.sites.map { it.name },
                        onRefresh = model::previewForecast, comparing = comparingPlan,
                        onStepChange = { comparingPlan = it }, onReturnToToday = { nav.popBackStack() },
                        onAccept = { window, context -> model.savePlan(window, context) { nav.popBackStack() } })
                }
                composable("break") { BreakScreen(model) { nav.popBackStack() } }
                composable("records") {
                    RecordsScreen(
                        model.sessions,
                        { nav.navigate("detail/$it") },
                        { nav.navigate("edit/0") },
                        { nav.navigate("search") })
                }
                composable("search") { SearchScreen(model.sessions) { nav.navigate("detail/$it") } }
                composable("detail/{id}") { detail ->
                    val id = detail.arguments?.getString("id")?.toIntOrNull()
                    val session = model.sessions.find { it.id == id }
                    if (session != null) SessionDetailScreen(
                        session,
                        { nav.navigate("edit/${session.id}") },
                        { model.deleteRecord(session.id) {
                            nav.navigate("records") { popUpTo("records") { inclusive = true }; launchSingleTop = true }
                        } })
                    else PageColumn {
                        Text("This record is no longer available."); PrimaryButton("Return to records") {
                        nav.navigate(
                            "records"
                        )
                    }
                    }
                }
                composable("edit/{id}") { edit ->
                    val id = edit.arguments?.getString("id")?.toIntOrNull()
                    val session = model.sessions.find { it.id == id }
                    if (id != 0 && session == null) PageColumn { Text("This record is no longer available.") }
                    else EditRecordScreen(session, onSave = { updated ->
                        model.saveRecord(updated) { nav.popBackStack() }
                    }, saving = model.busy, sites = model.sites.map { it.name }, defaultSite = model.preferences.site)
                }
                composable("trends") { TrendsScreen(model.sessions) }
                composable("profile") { ProfileScreen(model, onSaved = {
                    nav.navigate("today") { popUpTo("today") { inclusive = true } }
                }, onSignOut = model::signOut) }
            }
        }
    }
}
