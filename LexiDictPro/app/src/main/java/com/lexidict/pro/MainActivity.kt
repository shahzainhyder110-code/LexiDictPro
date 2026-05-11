package com.lexidict.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lexidict.pro.feature.dictionaries.DictionariesScreen
import com.lexidict.pro.feature.favorites.FavoritesScreen
import com.lexidict.pro.feature.history.HistoryScreen
import com.lexidict.pro.feature.import_manager.ImportScreen
import com.lexidict.pro.feature.ocr.OcrCameraScreen
import com.lexidict.pro.feature.search.SearchScreen
import com.lexidict.pro.feature.settings.SettingsScreen
import com.lexidict.pro.ui.theme.LexiDictProTheme
import dagger.hilt.android.AndroidEntryPoint

// ─── Route constants ──────────────────────────────────────────────────────────

sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val showInBottomNav: Boolean = true
) {
    object Search      : Screen("search",      "Search",      Icons.Filled.Search,      Icons.Outlined.Search)
    object Dictionaries: Screen("dictionaries","Dictionaries",Icons.Filled.MenuBook,    Icons.Outlined.MenuBook)
    object Favorites   : Screen("favorites",   "Favorites",   Icons.Filled.Bookmark,    Icons.Outlined.BookmarkBorder)
    object History     : Screen("history",     "History",     Icons.Filled.History,     Icons.Outlined.History)
    object Settings    : Screen("settings",    "Settings",    Icons.Filled.Settings,    Icons.Outlined.Settings)
    object Import      : Screen("import",      "Import",      Icons.Filled.FileOpen,    Icons.Outlined.FileOpen,     showInBottomNav = false)
    object Ocr         : Screen("ocr",         "OCR",         Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner, showInBottomNav = false)
}

private val bottomNavScreens = listOf(
    Screen.Search, Screen.Dictionaries, Screen.Favorites, Screen.History, Screen.Settings
)

// ─── Activity ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LexiDictProTheme {
                LexiDictApp()
            }
        }
    }
}

// ─── Root composable ──────────────────────────────────────────────────────────

@Composable
fun LexiDictApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = bottomNavScreens.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NavigationBar {
                    bottomNavScreens.forEach { screen ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Search.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Search.route) {
                SearchScreen(
                    onNavigateToImport = { navController.navigate(Screen.Import.route) },
                    onNavigateToOcr    = { navController.navigate(Screen.Ocr.route) }
                )
            }
            composable(Screen.Dictionaries.route) {
                DictionariesScreen(
                    onNavigateToImport = { navController.navigate(Screen.Import.route) }
                )
            }
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    onWordClick = { word ->
                        navController.navigate(Screen.Search.route) {
                            launchSingleTop = true
                        }
                        // Pass word to search — handled via SharedViewModel or SavedStateHandle
                    }
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    onWordClick = { _ ->
                        navController.navigate(Screen.Search.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable(Screen.Import.route) {
                ImportScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Ocr.route) {
                OcrCameraScreen(
                    onWordSelected = { word ->
                        navController.popBackStack()
                        navController.navigate(Screen.Search.route) {
                            launchSingleTop = true
                        }
                    },
                    onClose = { navController.popBackStack() }
                )
            }
        }
    }
}

// ─── Application class ────────────────────────────────────────────────────────

@dagger.hilt.android.HiltAndroidApp
class LexiDictApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
