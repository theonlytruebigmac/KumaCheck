package app.kumacheck.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.kumacheck.data.auth.NotificationMode
import app.kumacheck.data.auth.ThemeMode
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.notify.Notifications
import app.kumacheck.ui.common.KumaTimePickerDialog
import app.kumacheck.ui.theme.*

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onSignedOut: () -> Unit,
    onOpenMaintenanceList: () -> Unit,
    onOpenManageList: () -> Unit,
    onAddServer: () -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()

    LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }

    // ST1: explicit confirmation dialogs for sign-out and per-server delete.
    // Both actions are one-tap and irreversible (sign-out drops the active
    // session; remove drops the server entry + all per-server prefs);
    // an accidental tap shouldn't be all it takes.
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmRemove by remember {
        mutableStateOf<app.kumacheck.data.auth.ServerEntry?>(null)
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This clears the session token for the active server and " +
                        "stops the foreground monitor service. You'll need to " +
                        "sign in again next time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut()
                }) { Text("Sign out", color = KumaDown) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    confirmRemove?.let { pending ->
        val isLast = ui.servers.size <= 1
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(if (isLast) "Remove your only server?" else "Remove this server?") },
            text = {
                Text(
                    if (isLast) {
                        "This drops the server entry and signs you out — there " +
                            "are no other saved servers to fall back to."
                    } else {
                        "This drops \"${pending.url}\" and its saved session, " +
                            "pins, and mute state. Other servers stay intact."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = pending.id
                    confirmRemove = null
                    vm.removeServer(id)
                }) { Text("Remove", color = KumaDown) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(KumaCream).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { SettingsHeader() }

        if (ui.tokensStoredPlaintext) {
            item { Spacer(Modifier.height(4.dp)) }
            item { PlaintextTokenBanner() }
        }
        if (ui.keystoreUnavailableForWrite) {
            item { Spacer(Modifier.height(4.dp)) }
            item { KeystoreUnavailableBanner() }
        }
        if (ui.activeServerInsecureCleartext) {
            item { Spacer(Modifier.height(4.dp)) }
            item { InsecureCleartextBanner() }
        }
        ui.migrationFailure?.let { msg ->
            item { Spacer(Modifier.height(4.dp)) }
            item { MigrationFailureBanner(msg) }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Server") }
        item { ServerCard(ui) }
        if (ui.servers.size > 1) {
            items(ui.servers, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    isActive = server.id == ui.activeServerId,
                    onSwitch = { vm.switchServer(server.id) },
                    onRemove = { confirmRemove = server },
                )
            }
        }
        item { AddServerEntry(onClick = onAddServer) }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Notifications") }
        item {
            NotificationModeCard(
                ui = ui,
                onSelect = vm::setNotificationMode,
            )
        }
        if (ui.notificationMode == NotificationMode.INSTANT_NTFY) {
            item {
                NtfyConfigCard(
                    serverUrl = ui.ntfyServerUrl,
                    topic = ui.ntfyTopic,
                    onSave = vm::setNtfyConfig,
                )
            }
        }
        if (ui.notificationMode == NotificationMode.INSTANT_NTFY ||
            ui.notificationMode == NotificationMode.LIVE_MONITORING) {
            item { ReliabilityChecklistCard() }
        }
        if (ui.notificationsEnabled) {
            item {
                QuietHoursCard(
                    enabled = ui.quietHoursEnabled,
                    startMinute = ui.quietHoursStartMinute,
                    endMinute = ui.quietHoursEndMinute,
                    onToggle = vm::setQuietHoursEnabled,
                    onStartChange = vm::setQuietHoursStart,
                    onEndChange = vm::setQuietHoursEnd,
                )
            }
            item { SendTestEntry() }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Appearance") }
        item { ThemeModeCard(ui.themeMode, vm::setThemeMode) }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Manage") }
        item {
            NavEntry(
                icon = Icons.Filled.Build,
                title = "Monitors",
                subtitle = "Pause, resume, or add monitors",
                onClick = onOpenManageList,
            )
        }
        item {
            NavEntry(
                icon = Icons.Filled.CalendarMonth,
                title = "Maintenance",
                subtitle = "Scheduled windows and recurring jobs",
                onClick = onOpenMaintenanceList,
            )
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("About") }
        item { AboutCard(ui) }

        item { Spacer(Modifier.height(8.dp)) }
        item { SignOutButton(onClick = { confirmSignOut = true }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PlaintextTokenBanner() {
    // Surfaced when the AndroidKeyStore was unavailable at the time we tried
    // to encrypt the session token, so the token is sitting in DataStore in
    // plaintext. Auto-clears the next time a token write succeeds against a
    // healthy keystore. We don't offer a dismiss action — silencing the
    // warning while the underlying condition persists is worse than the
    // visual cost of leaving it visible.
    //
    // A11Y5: marked as an Assertive live region — this is security-relevant,
    // so a screen-reader user should hear about it the moment it appears,
    // not only when they manually navigate past it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KumaCardCorner))
            .background(KumaWarnBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = KumaWarn,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Session token stored in plaintext",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "This device's keystore was unavailable when KumaCheck saved your sign-in. " +
                        "Your token is not hardware-protected — sign out before sharing or selling this device. " +
                        "The warning will clear automatically once the keystore is healthy on next sign-in.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun KeystoreUnavailableBanner() {
    // S1: surfaced when [KumaPrefs.encodeTokenForStorage] returned null on
    // the most recent write attempt — the in-memory token is fine for
    // this session but won't survive a restart. Sign-out + sign-in once
    // the keystore is healthy is the recovery path.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KumaCardCorner))
            .background(KumaWarnBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = KumaWarn,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Secure storage unavailable",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Your sign-in is held in memory but couldn't be encrypted to disk. " +
                        "It will be lost when the app restarts. Sign in again once your " +
                        "device's keystore is healthy.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun InsecureCleartextBanner() {
    // S2: surfaced when the active server URL is `http://` and the host
    // doesn't look LAN-private (see [isInsecureCleartextUrl]). The login
    // screen warns once at sign-in; this re-asserts every session in case
    // the user followed a phishing redirect to `http://attacker.com:3001`.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KumaCardCorner))
            .background(KumaWarnBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = KumaWarn,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Cleartext server",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "This server uses http:// over a non-private host. Your " +
                        "session token is sent unencrypted. Switch to https:// " +
                        "if you didn't intend this.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun MigrationFailureBanner(message: String) {
    // O3: a startup migration threw. The user's session may not have come
    // through cleanly; signing out + back in is the recovery path. We
    // include the underlying error message to help with bug reports.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KumaCardCorner))
            .background(KumaWarnBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = KumaWarn,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Session migration failed",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$message Sign out and sign back in to recover.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader() {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            "PREFERENCES",
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = KumaTypography.caption,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Settings",
            color = KumaInk,
            fontFamily = KumaFont,
            fontWeight = FontWeight.Bold,
            fontSize = KumaTypography.display,
            letterSpacing = (-0.6).sp,
        )
    }
}

@Composable
private fun ServerCard(ui: SettingsViewModel.UiState) {
    val (statusLabel, statusColor) = when (ui.connection) {
        KumaSocket.Connection.AUTHENTICATED -> "Connected" to KumaUp
        KumaSocket.Connection.LOGIN_REQUIRED -> "Sign in required" to KumaWarn
        KumaSocket.Connection.CONNECTED -> "Authenticating" to KumaWarn
        KumaSocket.Connection.CONNECTING -> "Connecting" to KumaSlate2
        KumaSocket.Connection.ERROR -> "Connection error" to KumaDown
        KumaSocket.Connection.DISCONNECTED -> "Offline" to KumaSlate2
    }

    KumaCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Cloud, contentDescription = null, tint = KumaSlate)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.serverUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
                        ?: "(no server)",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.size(7.dp).clip(RoundedCornerShape(50))
                            .background(statusColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusLabel,
                        color = statusColor,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = KumaTypography.captionLarge,
                    )
                    if (ui.username != null) {
                        Text(
                            "  ·  ${ui.username}",
                            color = KumaSlate2,
                            fontFamily = KumaFont,
                            fontSize = KumaTypography.captionLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: app.kumacheck.data.auth.ServerEntry,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    val displayUrl = server.url
        .removePrefix("https://").removePrefix("http://")
        .trimEnd('/')
    KumaCard(onClick = if (isActive) null else onSwitch) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50))
                    .background(if (isActive) KumaTerra else KumaSlate2.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayUrl,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = KumaTypography.body,
                    maxLines = 1,
                )
                Text(
                    server.username ?: "no session",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.caption,
                )
            }
            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = KumaTerra.copy(alpha = 0.12f),
                ) {
                    Text(
                        "ACTIVE",
                        color = KumaTerra2,
                        fontFamily = KumaMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            // A11Y8: drop explicit 36.dp size — the default 48dp meets the
            // Material minimum touch target.
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove server",
                    tint = KumaDown.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AddServerEntry(onClick: () -> Unit) {
    KumaCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(50))
                    .background(KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = KumaSlate)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Add another server",
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.bodyEmphasis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = KumaSlate2)
        }
    }
}

/**
 * Notification delivery mode picker. Replaces the on/off Switch with a
 * 4-option chooser (Off / Instant ntfy / Live monitoring / Battery saver).
 *
 * Permission flow: any non-OFF selection requires POST_NOTIFICATIONS. We
 * intercept the click, request the permission, and only persist the new
 * mode once granted. If the user revokes the permission later (system
 * Settings), the ON_RESUME observer flips the mode back to OFF so prefs
 * never disagree with what's actually possible.
 */
@Composable
private fun NotificationModeCard(
    ui: SettingsViewModel.UiState,
    onSelect: (NotificationMode) -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var hasPostPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    /** The mode the user wanted right before the permission prompt. */
    var pendingMode by remember { mutableStateOf<NotificationMode?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                hasPostPerm = granted
                if (!granted && ui.notificationMode != NotificationMode.OFF) {
                    onSelect(NotificationMode.OFF)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPostPerm = granted
        if (granted) {
            permanentlyDenied = false
            pendingMode?.let(onSelect)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null) {
            permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        pendingMode = null
    }

    val openAppNotificationSettings = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    val request: (NotificationMode) -> Unit = { wanted ->
        if (wanted == NotificationMode.OFF || hasPostPerm) {
            onSelect(wanted)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (permanentlyDenied) {
                openAppNotificationSettings()
            } else {
                pendingMode = wanted
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            hasPostPerm = true
            onSelect(wanted)
        }
    }

    val showPermWarning = ui.notificationMode != NotificationMode.OFF && !hasPostPerm

    KumaCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(
                            if (ui.notificationMode != NotificationMode.OFF)
                                KumaTerra.copy(alpha = 0.12f)
                            else KumaCream2,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = if (ui.notificationMode != NotificationMode.OFF)
                            KumaTerra
                        else KumaSlate,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Delivery",
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.bodyEmphasis,
                    )
                    Text(
                        when {
                            showPermWarning && permanentlyDenied ->
                                "Notifications blocked — tap below to retry"
                            showPermWarning ->
                                "Permission needed — tap a mode to retry"
                            else -> "How KumaCheck wakes you up for outages"
                        },
                        color = if (showPermWarning) KumaDown else KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ModeOptionRow(
                mode = NotificationMode.INSTANT_NTFY,
                selected = ui.notificationMode == NotificationMode.INSTANT_NTFY,
                title = "Instant alerts via ntfy",
                subtitle = "Recommended · light battery · per-monitor mute won't apply",
                badge = "RECOMMENDED",
                icon = Icons.Filled.Bolt,
                onClick = { request(NotificationMode.INSTANT_NTFY) },
            )
            ModeOptionRow(
                mode = NotificationMode.LIVE_MONITORING,
                selected = ui.notificationMode == NotificationMode.LIVE_MONITORING,
                title = "Live monitoring",
                subtitle = "Hold the Kuma connection open · instant alerts · moderate battery",
                badge = null,
                icon = Icons.Filled.Refresh,
                onClick = { request(NotificationMode.LIVE_MONITORING) },
            )
            ModeOptionRow(
                mode = NotificationMode.OFF,
                selected = ui.notificationMode == NotificationMode.OFF,
                title = "Off",
                subtitle = "No background notifications",
                badge = null,
                icon = null,
                onClick = { request(NotificationMode.OFF) },
            )
        }
    }
}

@Composable
private fun ModeOptionRow(
    mode: NotificationMode,
    selected: Boolean,
    title: String,
    subtitle: String,
    badge: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    @Suppress("UNUSED_PARAMETER") val _unused = mode
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) KumaTerra.copy(alpha = 0.10f) else KumaCream2,
        border = BorderStroke(
            1.dp,
            if (selected) KumaTerra.copy(alpha = 0.45f) else KumaCardBorder,
        ),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = KumaTerra,
                    unselectedColor = KumaSlate2,
                ),
            )
            Spacer(Modifier.width(4.dp))
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) KumaTerra2 else KumaSlate,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = KumaTypography.body,
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = KumaTerra.copy(alpha = 0.18f),
                        ) {
                            Text(
                                badge,
                                color = KumaTerra2,
                                fontFamily = KumaMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    subtitle,
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.caption,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

/**
 * Ntfy server URL + topic. Only shown when [NotificationMode.INSTANT_NTFY]
 * is selected. The "Generate" button mints a random topic so the user can
 * paste a unique URL into Kuma without inventing one. Setup steps in Kuma
 * are linked inline so the user knows what to do with the URL.
 */
@Composable
private fun NtfyConfigCard(
    serverUrl: String?,
    topic: String?,
    onSave: (String?, String?) -> Unit,
) {
    var localServer by androidx.compose.runtime.saveable.rememberSaveable(serverUrl) {
        mutableStateOf(serverUrl ?: DEFAULT_NTFY_SERVER)
    }
    var localTopic by androidx.compose.runtime.saveable.rememberSaveable(topic) {
        mutableStateOf(topic ?: "")
    }
    val configured = !serverUrl.isNullOrBlank() && !topic.isNullOrBlank()

    KumaCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(KumaCream2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = KumaSlate)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ntfy topic",
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.bodyEmphasis,
                    )
                    Text(
                        if (configured) "Configured · waiting for alerts"
                        else "Required · paste topic URL or generate one",
                        color = if (configured) KumaUp else KumaWarn,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = localServer,
                onValueChange = { localServer = it },
                singleLine = true,
                label = { Text("Server") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = localTopic,
                onValueChange = { localTopic = it },
                singleLine = true,
                label = { Text("Topic") },
                trailingIcon = {
                    IconButton(onClick = { localTopic = randomNtfyTopic() }) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Generate random topic",
                            tint = KumaSlate,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "In Kuma: Settings → Notifications → Setup notification → " +
                    "type \"ntfy\" → use this server and topic → tick \"Apply " +
                    "on all existing monitors.\" To avoid duplicates, set " +
                    "this as your only default notification (no other " +
                    "providers on the same monitors).",
                color = KumaSlate2,
                fontFamily = KumaFont,
                fontSize = KumaTypography.caption,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        onSave(
                            localServer.takeIf { it.isNotBlank() },
                            localTopic.takeIf { it.isNotBlank() },
                        )
                    },
                ) { Text("Save", color = KumaTerra2) }
            }
        }
    }
}

/**
 * On-device reliability hints. Battery optimization exemption and exact
 * alarm permission both meaningfully improve background reliability for
 * the FGS-backed modes; this card surfaces their state and lets the user
 * fix them in one tap.
 */
@Composable
private fun ReliabilityChecklistCard() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var ignoresBatteryOpt by remember {
        mutableStateOf(checkIgnoresBatteryOptimizations(ctx))
    }
    var canScheduleExact by remember { mutableStateOf(checkCanScheduleExact(ctx)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoresBatteryOpt = checkIgnoresBatteryOptimizations(ctx)
                canScheduleExact = checkCanScheduleExact(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KumaCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "Background reliability",
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.bodyEmphasis,
            )
            Text(
                "These permissions help the OS keep KumaCheck running so " +
                    "alerts arrive even after hours of silence.",
                color = KumaSlate2,
                fontFamily = KumaFont,
                fontSize = KumaTypography.caption,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            ChecklistRow(
                granted = ignoresBatteryOpt,
                title = "Battery optimization exempt",
                rationale = "Stops Android from killing the monitor in deep sleep.",
                actionLabel = if (ignoresBatteryOpt) null else "Grant",
                onAction = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                },
            )
            Spacer(Modifier.height(8.dp))
            ChecklistRow(
                granted = canScheduleExact,
                title = "Exact alarms allowed",
                rationale = "Lets the watchdog wake every 9 min to revive a killed service.",
                actionLabel = if (canScheduleExact) null else "Grant",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChecklistRow(
    granted: Boolean,
    title: String,
    rationale: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50))
                .background(
                    if (granted) KumaUp.copy(alpha = 0.15f) else KumaWarnBg,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (granted) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) KumaUp else KumaWarn,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Medium,
                fontSize = KumaTypography.body,
            )
            Text(
                rationale,
                color = KumaSlate2,
                fontFamily = KumaFont,
                fontSize = KumaTypography.caption,
                lineHeight = 14.sp,
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = KumaTerra2)
            }
        }
    }
}

private const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"

private fun randomNtfyTopic(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val random = java.security.SecureRandom()
    return "kumacheck-" + (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

private fun checkIgnoresBatteryOptimizations(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun checkCanScheduleExact(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return false
    return am.canScheduleExactAlarms()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursCard(
    enabled: Boolean,
    startMinute: Int,
    endMinute: Int,
    onToggle: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    var showStart by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showEnd by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    KumaCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(KumaCream2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = KumaSlate)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Quiet hours",
                        color = KumaInk,
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = KumaTypography.bodyEmphasis,
                    )
                    Text(
                        "Silence alerts during a daily window",
                        color = KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = KumaSlate,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = KumaPaused,
                    ),
                )
            }
            if (enabled) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietHoursTimeSlot(
                        label = "From",
                        minuteOfDay = startMinute,
                        onClick = { showStart = true },
                        modifier = Modifier.weight(1f),
                    )
                    QuietHoursTimeSlot(
                        label = "To",
                        minuteOfDay = endMinute,
                        onClick = { showEnd = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showStart) {
        KumaTimePickerDialog(
            initialMinuteOfDay = startMinute,
            onConfirm = { onStartChange(it); showStart = false },
            onDismiss = { showStart = false },
        )
    }
    if (showEnd) {
        KumaTimePickerDialog(
            initialMinuteOfDay = endMinute,
            onConfirm = { onEndChange(it); showEnd = false },
            onDismiss = { showEnd = false },
        )
    }
}

@Composable
private fun QuietHoursTimeSlot(
    label: String,
    minuteOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = KumaCream2,
        border = BorderStroke(1.dp, KumaCardBorder),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                label,
                color = KumaSlate2,
                fontFamily = KumaFont,
                fontSize = KumaTypography.caption,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "%02d:%02d".format(h, m),
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.title,
            )
        }
    }
}

@Composable
private fun SendTestEntry() {
    val ctx = LocalContext.current
    var lastResultMsg by remember { mutableStateOf<String?>(null) }
    KumaCard(
        onClick = {
            val ok = Notifications.sendTest(ctx)
            lastResultMsg = if (ok) "Test sent." else "Permission missing."
        },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(50))
                    .background(KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = KumaSlate)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Send a test notification",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                if (lastResultMsg != null) {
                    Text(
                        lastResultMsg!!,
                        color = if (lastResultMsg!!.startsWith("Permission")) KumaDown else KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = KumaTypography.captionLarge,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = KumaSlate2)
        }
    }
}

@Composable
private fun ThemeModeCard(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    KumaCard {
        Row(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThemeModeChip(
                icon = Icons.Filled.LightMode,
                label = "Light",
                selected = current == ThemeMode.LIGHT,
                onClick = { onChange(ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f),
            )
            ThemeModeChip(
                icon = Icons.Filled.DarkMode,
                label = "Dark",
                selected = current == ThemeMode.DARK,
                onClick = { onChange(ThemeMode.DARK) },
                modifier = Modifier.weight(1f),
            )
            ThemeModeChip(
                icon = Icons.Filled.Smartphone,
                label = "System",
                selected = current == ThemeMode.SYSTEM,
                onClick = { onChange(ThemeMode.SYSTEM) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ThemeModeChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) KumaTerra.copy(alpha = 0.12f) else KumaCream2,
        border = BorderStroke(1.dp, if (selected) KumaTerra.copy(alpha = 0.4f) else KumaCardBorder),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) KumaTerra2 else KumaSlate,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = if (selected) KumaTerra2 else KumaInk,
                fontFamily = KumaFont,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = KumaTypography.captionLarge,
            )
        }
    }
}

@Composable
private fun NavEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    KumaCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = KumaSlate)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                )
                Text(
                    subtitle,
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = KumaTypography.captionLarge,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = KumaSlate2)
        }
    }
}

@Composable
private fun AboutCard(ui: SettingsViewModel.UiState) {
    KumaCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            AboutRow(label = "App version", value = ui.appVersion)
            AboutDivider()
            AboutRow(
                label = "Uptime Kuma",
                value = buildString {
                    append(ui.info?.version ?: "—")
                    val latest = ui.info?.latestVersion
                    if (latest != null && latest != ui.info.version) append(" (latest $latest)")
                },
            )
            AboutDivider()
            AboutRow(label = "Database", value = ui.info?.dbType?.uppercase() ?: "—")
            AboutDivider()
            AboutRow(label = "Timezone", value = ui.info?.timezone ?: "—")
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = KumaInk,
            fontFamily = KumaFont,
            fontSize = KumaTypography.body,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = KumaTypography.captionLarge,
        )
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        color = KumaCardBorder,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SignOutButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KumaCardCorner),
        color = Color.Transparent,
        border = BorderStroke(1.dp, KumaDown.copy(alpha = 0.25f)),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = KumaDown,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Sign out",
                color = KumaDown,
                fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.bodyEmphasis,
            )
        }
    }
}
