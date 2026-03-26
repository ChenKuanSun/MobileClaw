package ai.affiora.mobileclaw.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.affiora.mobileclaw.data.prefs.UserPreferences
import ai.affiora.mobileclaw.ui.chat.ChatScreen
import ai.affiora.mobileclaw.ui.cron.CronScreen
import ai.affiora.mobileclaw.ui.devices.DevicesScreen
import ai.affiora.mobileclaw.ui.onboarding.OnboardingScreen
import ai.affiora.mobileclaw.ui.settings.SettingsScreen
import ai.affiora.mobileclaw.ui.skills.SkillsScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

// ── Route definitions ────────────────────────────────────────────────────────

@Serializable data object ChatRoute
@Serializable data object DevicesRoute
@Serializable data object SkillsRoute
@Serializable data object CronRoute
@Serializable data object SettingsRoute
@Serializable data object OnboardingRoute

data class BottomNavItem(
    val label: String,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@Composable
fun MobileClawApp(userPreferences: UserPreferences) {
    val navController = rememberNavController()

    // Check onboarding status asynchronously to avoid runBlocking on main thread
    var isLoading by remember { mutableStateOf(true) }
    var onboardingCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onboardingCompleted = userPreferences.onboardingCompleted.first()
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination: Any = if (onboardingCompleted) ChatRoute else OnboardingRoute

    val bottomNavItems = remember {
        listOf(
            BottomNavItem("Chat", ChatRoute, Icons.Filled.Chat, Icons.Outlined.Chat),
            BottomNavItem("Devices", DevicesRoute, Icons.Filled.Devices, Icons.Outlined.Devices),
            BottomNavItem("Skills", SkillsRoute, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
            BottomNavItem("Schedule", CronRoute, Icons.Filled.Schedule, Icons.Outlined.Schedule),
            BottomNavItem("Settings", SettingsRoute, Icons.Filled.Settings, Icons.Outlined.Settings),
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = bottomNavItems.any { item ->
        currentDestination?.hasRoute(item.route::class) == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hasRoute(item.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onOnboardingComplete = {
                        navController.navigate(ChatRoute) {
                            popUpTo(OnboardingRoute) { inclusive = true }
                        }
                    },
                )
            }
            composable<ChatRoute> {
                ChatScreen()
            }
            composable<DevicesRoute> {
                DevicesScreen()
            }
            composable<SkillsRoute> {
                SkillsScreen(
                    onNavigateToChat = {
                        navController.navigate(ChatRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable<CronRoute> {
                CronScreen()
            }
            composable<SettingsRoute> {
                SettingsScreen()
            }
        }
    }
}
