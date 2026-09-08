@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package heatshield.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
class PrototypeViewModel : ViewModel() {
    val sessions = mutableStateListOf<DemoSession>().apply { addAll(initialSessions) }
    var acceptedWindow by mutableStateOf("08:00–10:00")
    var checkInDone by mutableStateOf(false)
    var checkInDeferred by mutableStateOf(false)
}

data class MainDestination(val route: String, val title: String, val icon: ImageVector)

val mainDestinations = listOf(
    MainDestination("today", "Today", Icons.Outlined.WbSunny),
    MainDestination("records", "Records", Icons.Outlined.ListAlt),
    MainDestination("trends", "Trends", Icons.Outlined.BarChart),
    MainDestination("profile", "Profile", Icons.Outlined.PersonOutline)
)

@Composable
fun HeatShieldApp(model: PrototypeViewModel = viewModel()) {
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
    fun enterDemo() {
        nav.navigate("today") { popUpTo("login") { inclusive = true }; launchSingleTop = true }
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
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
        },
        bottomBar = {
            if (!signedOut) Column {
                HorizontalDivider(color = Line, thickness = 1.dp)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    mainDestinations.forEach { dest ->
                        NavigationBarItem(
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
                                selectedIconColor = Pine,
                                selectedTextColor = Pine,
                                indicatorColor = Mint,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
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
                    ShiftPlanScreen(model.acceptedWindow) {
                        model.acceptedWindow = it; nav.popBackStack()
                    }
                }
                composable("break") {
                    BreakScreen(model.sessions.find { it.id == 1 }?.outdoorMinutes ?: 0) { done ->
                        model.checkInDone = done; model.checkInDeferred = !done
                        val index = model.sessions.indexOfFirst { it.id == 1 }
                        if (index >= 0) model.sessions[index] = model.sessions[index].copy(
                            status = if (done) "Reviewed" else "Needs review",
                            checkInNote = if (done) "Break check-in completed in the demo. No duration was added." else "A work adjustment needs discussion. The follow-up remains open."
                        )
                        nav.popBackStack()
                    }
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
