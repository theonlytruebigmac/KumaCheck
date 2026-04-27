package app.kumacheck.ui.settings

import android.Manifest
import android.app.Activity
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.kumacheck.data.auth.ThemeMode
import app.kumacheck.data.socket.KumaSocket
import app.kumacheck.notify.Notifications
import app.kumacheck.ui.theme.*

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onSignedOut: () -> Unit,
    onOpenMaintenanceList: () -> Unit,
    onOpenManageList: () -> Unit,
    onAddServer: () -> Unit,
) {
    val ui by vm.state.collectAsState()
    val signedOut by vm.signedOut.collectAsState()

    LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }

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

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Server") }
        item { ServerCard(ui) }
        if (ui.servers.size > 1) {
            items(ui.servers, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    isActive = server.id == ui.activeServerId,
                    onSwitch = { vm.switchServer(server.id) },
                    onRemove = { vm.removeServer(server.id) },
                )
            }
        }
        item { AddServerEntry(onClick = onAddServer) }

        item { Spacer(Modifier.height(4.dp)) }
        item { KumaSectionHeader("Notifications") }
        item { NotificationsCard(ui, vm::setNotificationsEnabled) }
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
        item { SignOutButton(onClick = vm::signOut) }
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
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "This device's keystore was unavailable when KumaCheck saved your sign-in. " +
                        "Your token is not hardware-protected — sign out before sharing or selling this device. " +
                        "The warning will clear automatically once the keystore is healthy on next sign-in.",
                    color = KumaSlate,
                    fontFamily = KumaFont,
                    fontSize = 12.sp,
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
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Settings",
            color = KumaInk,
            fontFamily = KumaFont,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
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
                        fontSize = 12.sp,
                    )
                    if (ui.username != null) {
                        Text(
                            "  ·  ${ui.username}",
                            color = KumaSlate2,
                            fontFamily = KumaFont,
                            fontSize = 12.sp,
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
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Text(
                    server.username ?: "no session",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = 11.sp,
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
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = KumaSlate2)
        }
    }
}

@Composable
private fun NotificationsCard(
    ui: SettingsViewModel.UiState,
    onToggle: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var hasPostPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    // True once the user has denied + selected "don't ask again" — at that
    // point launcher.launch returns immediately without showing a dialog,
    // so we send them to the system app-notification settings instead.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPostPerm = granted
        if (granted) {
            permanentlyDenied = false
            onToggle(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null) {
            // After a denial, if the system says we should NOT show a
            // rationale, we're either pre-first-prompt (impossible here —
            // we just got a result) or the user picked "don't ask again."
            permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    val openAppNotificationSettings = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    val showPermWarning = ui.notificationsEnabled && !hasPostPerm
    val on = ui.notificationsEnabled && hasPostPerm

    KumaCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) KumaTerra.copy(alpha = 0.12f) else KumaCream2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null,
                    tint = if (on) KumaTerra else KumaSlate)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Push alerts",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    when {
                        showPermWarning && permanentlyDenied ->
                            "Notifications blocked — tap to open system settings"
                        showPermWarning -> "Permission denied — tap to retry"
                        on -> "On for incidents and recoveries"
                        else -> "Off"
                    },
                    color = if (showPermWarning) KumaDown else KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = on,
                onCheckedChange = { wantsOn ->
                    if (wantsOn) {
                        if (hasPostPerm) onToggle(true)
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (permanentlyDenied) openAppNotificationSettings()
                            else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            hasPostPerm = true
                            onToggle(true)
                        }
                    } else {
                        onToggle(false)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = KumaTerra,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = KumaPaused,
                ),
            )
        }
    }
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
                        fontSize = 14.sp,
                    )
                    Text(
                        "Silence alerts during a daily window",
                        color = KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = 12.sp,
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
        TimePickerDialog(
            initialMinuteOfDay = startMinute,
            onConfirm = { onStartChange(it); showStart = false },
            onDismiss = { showStart = false },
        )
    }
    if (showEnd) {
        TimePickerDialog(
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
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "%02d:%02d".format(h, m),
                color = KumaInk,
                fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = KumaSurface,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Pick a time",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = KumaSlate2, fontFamily = KumaFont)
                    }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text(
                            "OK", color = KumaTerra,
                            fontWeight = FontWeight.SemiBold, fontFamily = KumaFont,
                        )
                    }
                }
            }
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
                    fontSize = 14.sp,
                )
                if (lastResultMsg != null) {
                    Text(
                        lastResultMsg!!,
                        color = if (lastResultMsg!!.startsWith("Permission")) KumaDown else KumaSlate2,
                        fontFamily = KumaFont,
                        fontSize = 12.sp,
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
                fontSize = 12.sp,
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
                    fontSize = 14.sp,
                )
                Text(
                    subtitle,
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontSize = 12.sp,
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
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = KumaSlate2,
            fontFamily = KumaMono,
            fontSize = 12.sp,
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
                fontSize = 14.sp,
            )
        }
    }
}
