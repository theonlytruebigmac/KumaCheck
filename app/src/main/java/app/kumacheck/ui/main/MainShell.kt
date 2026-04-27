package app.kumacheck.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.ui.incident.IncidentsListScreen
import app.kumacheck.ui.incident.IncidentsListViewModel
import app.kumacheck.ui.monitors.MonitorsViewModel
import app.kumacheck.ui.overview.OverviewScreen
import app.kumacheck.ui.overview.OverviewViewModel
import app.kumacheck.ui.settings.SettingsScreen
import app.kumacheck.ui.settings.SettingsViewModel
import app.kumacheck.ui.statuspages.StatusPagesListScreen
import app.kumacheck.ui.statuspages.StatusPagesListViewModel
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaTerra

private enum class Tab(val label: String) {
    HOME("Home"), INCIDENTS("Incidents"), STATUS("Status"), SETTINGS("Settings"),
}

@Composable
fun MainShell(
    overviewVm: OverviewViewModel,
    monitorsVm: MonitorsViewModel,
    statusPagesVm: StatusPagesListViewModel,
    settingsVm: SettingsViewModel,
    incidentsVm: IncidentsListViewModel,
    connection: KumaSocket.Connection,
    onLoggedOut: () -> Unit,
    onMonitorTap: (Int) -> Unit,
    onIncidentTap: (Int) -> Unit,
    onMaintenanceTap: (Int) -> Unit,
    onOpenMaintenanceList: () -> Unit,
    onOpenStatusPage: (String) -> Unit,
    onOpenManageList: () -> Unit,
    onShowMonitorsFiltered: (MonitorsViewModel.Filter) -> Unit,
    onAddServer: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    val onOpenSettings: () -> Unit = { tab = Tab.SETTINGS }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = KumaCream,
        bottomBar = {
            HorizontalDivider(color = KumaCardBorder)
            // A11Y12: drop the explicit 72.dp height. M3 default (80dp) is
            // intentionally sized to fit icon + label at 200% font scale; a
            // hardcoded 72dp clips the label at large text scale.
            NavigationBar(
                containerColor = KumaCream,
            ) {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(Tab.HOME.label, fontFamily = KumaFont, fontWeight = FontWeight.Medium) },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == Tab.INCIDENTS,
                    onClick = { tab = Tab.INCIDENTS },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    label = { Text(Tab.INCIDENTS.label, fontFamily = KumaFont, fontWeight = FontWeight.Medium) },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == Tab.STATUS,
                    onClick = { tab = Tab.STATUS },
                    icon = { Icon(Icons.Filled.Public, contentDescription = null) },
                    label = { Text(Tab.STATUS.label, fontFamily = KumaFont, fontWeight = FontWeight.Medium) },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(Tab.SETTINGS.label, fontFamily = KumaFont, fontWeight = FontWeight.Medium) },
                    colors = navColors(),
                )
            }
        },
    ) { padding ->
        // Instant swap (no Crossfade): rapid bouncing between tabs would
        // otherwise overlap two heavy compositions during the 300ms fade,
        // and each tab is just lateral nav — no animation needed.
        // The status-bar inset is applied here (A11Y2) so all five tabbed
        // screens get top padding for free — none of them include their own
        // statusBars padding, and edgeToEdge in MainActivity means content
        // would otherwise render under the system status bar.
        Box(
            modifier = Modifier
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            when (tab) {
                Tab.HOME -> OverviewScreen(
                    vm = overviewVm,
                    connection = connection,
                    onMonitorTap = onMonitorTap,
                    onIncidentTap = onIncidentTap,
                    onShowMonitorsFiltered = onShowMonitorsFiltered,
                    onOpenSettings = onOpenSettings,
                    onMaintenanceTap = onMaintenanceTap,
                )
                Tab.INCIDENTS -> IncidentsListScreen(
                    vm = incidentsVm,
                    onIncidentTap = onIncidentTap,
                )
                Tab.STATUS -> StatusPagesListScreen(
                    vm = statusPagesVm,
                    onBack = { tab = Tab.HOME },
                    onPageTap = onOpenStatusPage,
                )
                Tab.SETTINGS -> SettingsScreen(
                    vm = settingsVm,
                    onSignedOut = onLoggedOut,
                    onOpenMaintenanceList = onOpenMaintenanceList,
                    onOpenManageList = onOpenManageList,
                    onAddServer = onAddServer,
                )
            }
        }
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = KumaTerra,
    selectedTextColor = KumaTerra,
    unselectedIconColor = KumaSlate2,
    unselectedTextColor = KumaSlate2,
    indicatorColor = Color.Transparent,
)
