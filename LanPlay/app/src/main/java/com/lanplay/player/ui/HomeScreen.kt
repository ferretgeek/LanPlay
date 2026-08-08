package com.lanplay.player.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanplay.player.data.prefs.HomeLayout
import com.lanplay.player.ui.browse.BrowseScreen
import com.lanplay.player.ui.tools.LibraryToolsScreen
import com.lanplay.player.ui.tools.ToolsMode
import com.lanplay.player.ui.settings.SettingsScreen
import com.lanplay.player.ui.history.HistoryScreen

@Composable
fun HomeScreen(
    initialTab: Int = 0,
    initialActorScreen: Boolean = false,
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    var actorScreenRequested by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(initialActorScreen)
    }
    var libraryOpened by rememberSaveable {
        androidx.compose.runtime.mutableStateOf(initialActorScreen)
    }
    var lastBackAt by rememberSaveable { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val homeLayout by dashboardViewModel.homeLayout.collectAsStateWithLifecycle()
    BackHandler {
        when {
            tab != 0 -> {
                tab = 0
                libraryOpened = false
            }
            homeLayout == HomeLayout.DASHBOARD && libraryOpened -> libraryOpened = false
            System.currentTimeMillis() - lastBackAt <= 2_000L -> (context as? Activity)?.finish()
            else -> {
                lastBackAt = System.currentTimeMillis()
                Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = {
                        tab = 0
                        libraryOpened = false
                    },
                    icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
                    label = { Text("媒体") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    label = { Text("历史") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                    label = { Text("清理") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    label = { Text("回收") },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> if (homeLayout == HomeLayout.DASHBOARD && !libraryOpened) {
                    DashboardScreen(
                        onOpenLibrary = { libraryOpened = true },
                        viewModel = dashboardViewModel,
                    )
                } else BrowseScreen(
                    initialActorScreen = actorScreenRequested,
                    onInitialActorConsumed = { actorScreenRequested = false },
                )
                1 -> HistoryScreen(onBrowse = {
                    tab = 0
                    libraryOpened = true
                })
                2 -> LibraryToolsScreen(
                    ToolsMode.CLEANUP,
                    onBrowse = {
                        tab = 0
                        libraryOpened = true
                    },
                )
                3 -> LibraryToolsScreen(
                    ToolsMode.TRASH,
                    onBrowse = {
                        tab = 0
                        libraryOpened = true
                    },
                )
                else -> SettingsScreen()
            }
        }
    }
}
