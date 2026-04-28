package app.kumacheck.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import app.kumacheck.ui.manage.ManageScreen
import app.kumacheck.ui.monitors.MonitorsScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.createSavedStateHandle
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.kumacheck.KumaCheckApp
import app.kumacheck.ui.detail.MonitorDetailScreen
import app.kumacheck.ui.detail.MonitorDetailViewModel
import app.kumacheck.ui.edit.MonitorEditScreen
import app.kumacheck.ui.edit.MonitorEditViewModel
import app.kumacheck.ui.incident.IncidentDetailScreen
import app.kumacheck.ui.incident.IncidentDetailViewModel
import app.kumacheck.ui.incident.IncidentsListViewModel
import app.kumacheck.ui.login.LoginScreen
import app.kumacheck.ui.login.LoginViewModel
import app.kumacheck.ui.main.MainShell
import app.kumacheck.ui.maintenance.MaintenanceDetailScreen
import app.kumacheck.ui.maintenance.MaintenanceDetailViewModel
import app.kumacheck.ui.maintenance.MaintenanceEditScreen
import app.kumacheck.ui.maintenance.MaintenanceEditViewModel
import app.kumacheck.ui.maintenance.MaintenanceListScreen
import app.kumacheck.ui.maintenance.MaintenanceListViewModel
import app.kumacheck.ui.manage.ManageViewModel
import app.kumacheck.ui.monitors.MonitorsViewModel
import app.kumacheck.ui.overview.OverviewViewModel
import app.kumacheck.ui.settings.SettingsViewModel
import app.kumacheck.ui.splash.SplashScreen
import app.kumacheck.ui.splash.SplashViewModel
import app.kumacheck.ui.statuspages.StatusPageDetailScreen
import app.kumacheck.ui.statuspages.StatusPageDetailViewModel
import app.kumacheck.ui.statuspages.StatusPagesListScreen
import app.kumacheck.ui.statuspages.StatusPagesListViewModel

private object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val LOGIN_ADD = "login/add"
    const val MAIN = "main"
    const val MONITOR = "monitor/{id}"
    const val MONITOR_EDIT = "monitor/{id}/edit"
    const val MONITOR_NEW = "monitor/new"
    const val MAINTENANCE_LIST = "maintenance"
    const val MAINTENANCE_DETAIL = "maintenance/{id}"
    const val MAINTENANCE_NEW = "maintenance/new"
    const val MAINTENANCE_EDIT = "maintenance/{id}/edit"
    const val STATUS_PAGES = "status_pages"
    const val STATUS_PAGE = "status_pages/{slug}"
    const val INCIDENT_DETAIL = "incident/{monitorId}"
    const val MONITORS_FILTERED = "monitors/{filter}"
    const val MANAGE_LIST = "manage"
    fun monitor(id: Int) = "monitor/$id"
    fun monitorEdit(id: Int) = "monitor/$id/edit"
    fun maintenance(id: Int) = "maintenance/$id"
    fun maintenanceEdit(id: Int) = "maintenance/$id/edit"
    fun statusPage(slug: String) = "status_pages/$slug"
    fun incidentDetail(monitorId: Int) = "incident/$monitorId"
    fun monitorsFiltered(filter: String) = "monitors/$filter"
}

@Composable
fun AppNav(
    app: KumaCheckApp,
    pendingMonitorId: () -> Int? = { null },
    onConsumeMonitorId: () -> Unit = {},
) {
    val nav = rememberNavController()
    // Q1: each route below uses `viewModelFactory { initializer { … } }` so a
    // VM construction lives next to the screen that needs it and a missing
    // VM is a compile error rather than a runtime crash from a forgotten
    // `when` case in a central factory.
    val connection by app.socket.connection.collectAsStateWithLifecycle()

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            val splashFactory = remember(app) {
                viewModelFactory { initializer { SplashViewModel(app.prefs, app.auth, app.socket) } }
            }
            val vm: SplashViewModel = viewModel(factory = splashFactory)
            SplashScreen(
                vm = vm,
                onToOverview = {
                    nav.navigate(Routes.MAIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onToLogin = {
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LOGIN) {
            val loginFactory = remember(app) {
                viewModelFactory {
                    initializer {
                        LoginViewModel(
                            app.auth, app.prefs, app.socket,
                            savedState = createSavedStateHandle(),
                        )
                    }
                }
            }
            val vm: LoginViewModel = viewModel(factory = loginFactory)
            LoginScreen(vm = vm, onAuthenticated = {
                nav.navigate(Routes.MAIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.LOGIN_ADD) {
            val loginAddFactory = remember(app) {
                viewModelFactory {
                    initializer {
                        LoginViewModel(
                            app.auth, app.prefs, app.socket,
                            savedState = createSavedStateHandle(),
                        )
                    }
                }
            }
            val vm: LoginViewModel = viewModel(factory = loginAddFactory)
            // Allocate a fresh server slot on entry so URL/credential edits
            // mutate the new entry rather than the current active server.
            // On back-pop without saving, `cancelAddIfEmpty` drops the
            // placeholder so we don't leave phantom blank entries behind.
            LaunchedEffect(Unit) { app.prefs.beginAddServer("") }
            LoginScreen(
                vm = vm,
                onAuthenticated = {
                    nav.navigate(Routes.MAIN) {
                        popUpTo(Routes.MAIN) { inclusive = false }
                    }
                },
                onBack = {
                    vm.cancelAddIfEmpty()
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.MAIN) {
            val overviewFactory = remember(app) {
                viewModelFactory { initializer { OverviewViewModel(app.socket, app.prefs) } }
            }
            val statusPagesFactory = remember(app) {
                viewModelFactory { initializer { StatusPagesListViewModel(app.socket) } }
            }
            val settingsFactory = remember(app) {
                viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            app.socket, app.auth, app.prefs,
                            appVersionName = appVersionName(app),
                            migrationFailureFlow = app.migrationFailureMessage,
                        )
                    }
                }
            }
            val incidentsFactory = remember(app) {
                viewModelFactory { initializer { IncidentsListViewModel(app.prefs) } }
            }
            val monitorsFactory = remember(app) {
                viewModelFactory { initializer { MonitorsViewModel(app.socket) } }
            }
            val overviewVm: OverviewViewModel = viewModel(factory = overviewFactory)
            val statusPagesVm: StatusPagesListViewModel = viewModel(factory = statusPagesFactory)
            val settingsVm: SettingsViewModel = viewModel(factory = settingsFactory)
            val incidentsVm: IncidentsListViewModel = viewModel(factory = incidentsFactory)

            // Consume any pending notification deep-link only once MAIN is on
            // screen — otherwise Splash's popUpTo would tear our pushed detail
            // back off the stack. Wired through snapshotFlow so the collect
            // sees each *new* non-null value exactly once: re-firing on the
            // same id would push the detail twice on the back stack.
            LaunchedEffect(Unit) {
                snapshotFlow { pendingMonitorId() }
                    .filterNotNull()
                    .collect { id ->
                        // Consume first, then navigate. Reverse order would
                        // leave a non-null pending id stranded if the user
                        // back-pressed during navigate (snapshotFlow only
                        // re-fires on state change, so the same id wouldn't
                        // re-trigger here without an external flip-to-null
                        // first).
                        onConsumeMonitorId()
                        nav.navigate(Routes.monitor(id))
                    }
            }

            MainShell(
                overviewVm = overviewVm,
                monitorsVm = viewModel(factory = monitorsFactory),
                statusPagesVm = statusPagesVm,
                settingsVm = settingsVm,
                incidentsVm = incidentsVm,
                connection = connection,
                socketErrors = app.socket.errors,
                onLoggedOut = {
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                onMonitorTap = { id -> nav.navigate(Routes.monitor(id)) },
                onIncidentTap = { id -> nav.navigate(Routes.incidentDetail(id)) },
                onMaintenanceTap = { id -> nav.navigate(Routes.maintenance(id)) },
                onOpenMaintenanceList = { nav.navigate(Routes.MAINTENANCE_LIST) },
                onOpenStatusPage = { slug -> nav.navigate(Routes.statusPage(slug)) },
                onOpenManageList = { nav.navigate(Routes.MANAGE_LIST) },
                onShowMonitorsFiltered = { filter ->
                    nav.navigate(Routes.monitorsFiltered(filter.name))
                },
                onAddServer = { nav.navigate(Routes.LOGIN_ADD) },
            )
        }
        composable(
            route = Routes.MONITOR,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val detailFactory = remember(app, id) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MonitorDetailViewModel(app.socket, app.prefs, id) as T
                }
            }
            val vm: MonitorDetailViewModel = viewModel(factory = detailFactory)
            MonitorDetailScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.monitorEdit(id)) },
            )
        }
        composable(
            route = Routes.MONITOR_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val editFactory = remember(app, id) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MonitorEditViewModel(app.socket, id) as T
                }
            }
            val vm: MonitorEditViewModel = viewModel(factory = editFactory)
            MonitorEditScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.MONITOR_NEW) {
            val newFactory = remember(app) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MonitorEditViewModel(app.socket, monitorId = null) as T
                }
            }
            val vm: MonitorEditViewModel = viewModel(factory = newFactory)
            MonitorEditScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onCreated = { newId ->
                    nav.navigate(Routes.monitor(newId)) {
                        popUpTo(Routes.MAIN)
                    }
                },
            )
        }
        composable(
            route = Routes.MONITORS_FILTERED,
            arguments = listOf(navArgument("filter") { type = NavType.StringType }),
        ) { entry ->
            val filter = entry.arguments?.getString("filter") ?: "ALL"
            val monitorsFactory = remember(app) {
                viewModelFactory { initializer { MonitorsViewModel(app.socket) } }
            }
            val vm: MonitorsViewModel = viewModel(factory = monitorsFactory)
            LaunchedEffect(filter) {
                val parsed = runCatching { MonitorsViewModel.Filter.valueOf(filter) }
                    .getOrDefault(MonitorsViewModel.Filter.ALL)
                vm.setFilter(parsed)
            }
            MonitorsScreenWithBack(
                vm = vm,
                onBack = { nav.popBackStack() },
                onMonitorTap = { id -> nav.navigate(Routes.monitor(id)) },
            )
        }
        composable(Routes.MANAGE_LIST) {
            val manageFactory = remember(app) {
                viewModelFactory { initializer { ManageViewModel(app.socket) } }
            }
            val vm: ManageViewModel = viewModel(factory = manageFactory)
            ManageScreenWithBack(
                vm = vm,
                onBack = { nav.popBackStack() },
                onMonitorTap = { id -> nav.navigate(Routes.monitor(id)) },
                onCreateMonitor = { nav.navigate(Routes.MONITOR_NEW) },
            )
        }
        composable(
            route = Routes.INCIDENT_DETAIL,
            arguments = listOf(navArgument("monitorId") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("monitorId") ?: return@composable
            val factory2 = remember(app, id) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        IncidentDetailViewModel(app.socket, app.prefs, id) as T
                }
            }
            val vm: IncidentDetailViewModel = viewModel(factory = factory2)
            IncidentDetailScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.STATUS_PAGES) {
            val listFactory = remember(app) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StatusPagesListViewModel(app.socket) as T
                }
            }
            val vm: StatusPagesListViewModel = viewModel(factory = listFactory)
            StatusPagesListScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onPageTap = { slug -> nav.navigate(Routes.statusPage(slug)) },
            )
        }
        composable(
            route = Routes.STATUS_PAGE,
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { entry ->
            val slug = entry.arguments?.getString("slug") ?: return@composable
            val detailFactory = remember(app, slug) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StatusPageDetailViewModel(app.socket, slug) as T
                }
            }
            val vm: StatusPageDetailViewModel = viewModel(factory = detailFactory)
            StatusPageDetailScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onMonitorTap = { id -> nav.navigate(Routes.monitor(id)) },
            )
        }
        composable(Routes.MAINTENANCE_LIST) {
            val maintListFactory = remember(app) {
                viewModelFactory { initializer { MaintenanceListViewModel(app.socket) } }
            }
            val vm: MaintenanceListViewModel = viewModel(factory = maintListFactory)
            MaintenanceListScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onMaintenanceTap = { id -> nav.navigate(Routes.maintenance(id)) },
                onCreateNew = { nav.navigate(Routes.MAINTENANCE_NEW) },
            )
        }
        composable(
            route = Routes.MAINTENANCE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val detailFactory = remember(app, id) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MaintenanceDetailViewModel(app.socket, id) as T
                }
            }
            val vm: MaintenanceDetailViewModel = viewModel(factory = detailFactory)
            MaintenanceDetailScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.maintenanceEdit(id)) },
                onMonitorTap = { mid -> nav.navigate(Routes.monitor(mid)) },
            )
        }
        composable(Routes.MAINTENANCE_NEW) {
            val editFactory = remember(app) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MaintenanceEditViewModel(app.socket, editingId = null) as T
                }
            }
            val vm: MaintenanceEditViewModel = viewModel(factory = editFactory)
            MaintenanceEditScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.MAINTENANCE_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            val editFactory = remember(app, id) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MaintenanceEditViewModel(app.socket, editingId = id) as T
                }
            }
            val vm: MaintenanceEditViewModel = viewModel(factory = editFactory)
            MaintenanceEditScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MonitorsScreenWithBack(
    vm: MonitorsViewModel,
    onBack: () -> Unit,
    onMonitorTap: (Int) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = app.kumacheck.ui.theme.KumaCream,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        "Monitors",
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontFamily = app.kumacheck.ui.theme.KumaFont,
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = app.kumacheck.ui.theme.KumaInk,
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = app.kumacheck.ui.theme.KumaCream,
                    titleContentColor = app.kumacheck.ui.theme.KumaInk,
                    navigationIconContentColor = app.kumacheck.ui.theme.KumaInk,
                ),
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MonitorsScreen(vm = vm, onMonitorTap = onMonitorTap)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ManageScreenWithBack(
    vm: ManageViewModel,
    onBack: () -> Unit,
    onMonitorTap: (Int) -> Unit,
    onCreateMonitor: () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        containerColor = app.kumacheck.ui.theme.KumaCream,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        "Manage",
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontFamily = app.kumacheck.ui.theme.KumaFont,
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = app.kumacheck.ui.theme.KumaInk,
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = app.kumacheck.ui.theme.KumaCream,
                    titleContentColor = app.kumacheck.ui.theme.KumaInk,
                    navigationIconContentColor = app.kumacheck.ui.theme.KumaInk,
                ),
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ManageScreen(
                vm = vm,
                onMonitorTap = onMonitorTap,
                onCreateMonitor = onCreateMonitor,
            )
        }
    }
}

/** ST3: read versionName from the installed APK's PackageInfo. */
private fun appVersionName(app: KumaCheckApp): String = runCatching {
    val pm = app.packageManager
    val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(app.packageName, 0)
    }
    info.versionName
}.getOrNull().orEmpty()

