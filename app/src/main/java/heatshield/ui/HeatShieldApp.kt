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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*

class PrototypeViewModel : ViewModel() {
    val sessions = mutableStateListOf<DemoSession>().apply { addAll(initialSessions) }
    var acceptedWindow by mutableStateOf("08:00–10:00")
    var preferredWorkContext by mutableStateOf(WorkContext())
    var checkInDone by mutableStateOf(false)
    var checkInDeferred by mutableStateOf(false)
    var contextUnavailable by mutableStateOf(false)
    var pauseConstraint by mutableStateOf("")
}

data class MainDestination(val route: String, val title: String, val icon: ImageVector)

val mainDestinations = listOf(
    MainDestination("today", "Today", Icons.Outlined.WbSunny),
    MainDestination("records", "Log", Icons.AutoMirrored.Outlined.ListAlt),
    MainDestination("trends", "Trends", Icons.Outlined.BarChart),
    MainDestination("profile", "Profile", Icons.Outlined.PersonOutline)
)

@Composable
fun HeatShieldApp(model: PrototypeViewModel = viewModel()) {
    val fontScale = LocalDensity.current.fontScale
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "login"
    val signedOut = route == "login" || route == "signup"
    val parent = when {
        route.startsWith("detail") || route.startsWith("edit") || route == "search" -> "records"
        route == "plan" || route == "break" -> "today"
        else -> route
    }
    val title = when (route) {
        "login" -> "HeatShield"; "signup" -> "Create account"; "today" -> "Today"
        "plan" -> "Shift plan"; "records" -> "Work records"; "search" -> "Search records"
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

    fun enterDemo() {
        nav.navigate("today") { popUpTo("login") { inclusive = true }; launchSingleTop = true }
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
                        if (isSecondary || route == "signup") IconButton(onClick = { nav.popBackStack() }) {
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
                                onClick = {
                                    nav.navigate(dest.route) {
                                        popUpTo("today") {
                                            saveState = true
                                        }; launchSingleTop = true; restoreState = true
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(nav, startDestination = "login") {
                composable("login") { LoginScreen(onLogin = { enterDemo() }, onSignUp = { nav.navigate("signup") }) }
                composable("signup") { SignUpScreen(onCreated = { enterDemo() }, onLogin = { nav.popBackStack() }) }
                composable("today") {
                    TodayScreen(
                        model,
                        { nav.navigate("plan") },
                        { nav.navigate("break") },
                        { nav.navigate(if (model.sessions.any { it.id == 1 }) "detail/1" else "records") })
                }
                composable("plan") {
                    ShiftPlanScreen(
                        model.acceptedWindow,
                        model.contextUnavailable,
                        model.preferredWorkContext,
                        onReturnToToday = { nav.popBackStack() }) { window, context ->
                        model.acceptedWindow = window
                        model.preferredWorkContext = context
                        nav.popBackStack()
                    }
                }
                composable("break") {
                    BreakScreen(
                        model.sessions.find { it.id == 1 }?.outdoorMinutes ?: 0,
                        model.sessions.find { it.id == 1 }?.title ?: "Outdoor work",
                        onRecord = { done, response ->
                            model.checkInDone = done; model.checkInDeferred = !done
                            model.pauseConstraint = if (done) "" else response
                            val index = model.sessions.indexOfFirst { it.id == 1 }
                            if (index >= 0) model.sessions[index] = model.sessions[index].copy(
                                status = if (done) "Reviewed" else "Needs review",
                                checkInNote = if (done) "Break check-in completed. No duration was added." else "$response. A work adjustment needs discussion. The follow-up remains open."
                            )
                        },
                        onReturn = { nav.popBackStack() })
                }
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
                        {
                            model.sessions.remove(session); nav.navigate("records") {
                            popUpTo("records") {
                                inclusive = true
                            }
                        }
                        })
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
                    EditRecordScreen(session) { updated ->
                        val existing = model.sessions.indexOfFirst { it.id == id }
                        if (existing >= 0) model.sessions[existing] = updated else model.sessions.add(
                            0,
                            updated.copy(id = (model.sessions.maxOfOrNull { it.id } ?: 0) + 1)
                        )
                        nav.popBackStack()
                    }
                }
                composable("trends") { TrendsScreen(model.sessions) }
                composable("profile") { ProfileScreen { nav.navigate("login") { popUpTo(0) { inclusive = true } } } }
            }
        }
    }
}
