package app.kumacheck.ui.main

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
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
    socketErrors: Flow<String>,
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

    // O1: surface socket-level errors (connect failures, etc.) as a
    // transient snackbar so they don't vanish into the SharedFlow void.
    // Hosted at the shell level so any tab can see them; transient
    // duration so the message doesn't camp on screen indefinitely.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(socketErrors) {
        socketErrors.collect { msg ->
            snackbarHost.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = KumaCream,
        snackbarHost = { SnackbarHost(snackbarHost) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // UX1: persistent banner whenever the socket isn't fully
            // authenticated. Without this, a tab user has no signal that
            // the data they're staring at is stale (the only connection
            // status indicator was buried in Settings → Server). Hidden
            // when AUTHENTICATED so the happy path stays unobtrusive.
            ConnectionBanner(connection)
            Box(modifier = Modifier.fillMaxSize()) {
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
}

@Composable
private fun ConnectionBanner(connection: KumaSocket.Connection) {
    val (label, bg, fg) = when (connection) {
        KumaSocket.Connection.AUTHENTICATED -> return
        KumaSocket.Connection.CONNECTED ->
            Triple("Connecting…", app.kumacheck.ui.theme.KumaWarnBg, app.kumacheck.ui.theme.KumaWarn)
        KumaSocket.Connection.CONNECTING ->
            Triple("Connecting…", app.kumacheck.ui.theme.KumaWarnBg, app.kumacheck.ui.theme.KumaWarn)
        KumaSocket.Connection.LOGIN_REQUIRED ->
            Triple("Reconnecting…", app.kumacheck.ui.theme.KumaWarnBg, app.kumacheck.ui.theme.KumaWarn)
        KumaSocket.Connection.DISCONNECTED ->
            Triple("Offline — data may be stale", app.kumacheck.ui.theme.KumaDownBg, app.kumacheck.ui.theme.KumaDown)
        KumaSocket.Connection.ERROR ->
            Triple("Connection error — pull to refresh", app.kumacheck.ui.theme.KumaDownBg, app.kumacheck.ui.theme.KumaDown)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(bg)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(fg),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = fg,
            fontFamily = KumaFont,
            fontWeight = FontWeight.Medium,
            fontSize = KumaTypography.captionLarge,
        )
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
