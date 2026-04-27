package app.kumacheck.ui.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kumacheck.data.model.Monitor
import app.kumacheck.ui.theme.KumaAccentCard
import app.kumacheck.ui.theme.KumaCard
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCardCorner
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaDown
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSectionHeader
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaSurface
import app.kumacheck.ui.theme.KumaTerra
import app.kumacheck.ui.theme.KumaUp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceEditScreen(
    vm: MaintenanceEditViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.state.collectAsState()
    val f = ui.form

    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }

    var showStartDate by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showEndDate by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showStartTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showEndTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showMonitorPicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ui.isEditing) "Edit maintenance" else "New maintenance",
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = KumaFont,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = vm::save,
                        enabled = !ui.isSaving && !ui.isLoading,
                    ) {
                        if (ui.isSaving) {
                            CircularProgressIndicator(
                                color = KumaUp, strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text("Save", color = KumaTerra, fontWeight = FontWeight.SemiBold,
                                fontFamily = KumaFont)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KumaCream,
                    titleContentColor = KumaInk,
                    navigationIconContentColor = KumaInk,
                ),
            )
        },
    ) { padding ->
        if (ui.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = KumaUp)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (ui.error != null) {
                item {
                    KumaAccentCard(accent = KumaDown.copy(alpha = 0.6f), onClick = vm::dismissError) {
                        Text(
                            ui.error!!,
                            color = KumaDown,
                            fontFamily = KumaMono,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            item { KumaSectionHeader("Basic") }
            item {
                LabelledField("title") {
                    KumaTextField(f.title, vm::onTitle)
                }
            }
            item {
                LabelledField("description") {
                    KumaTextField(
                        f.description, vm::onDescription,
                        placeholder = "Optional notes",
                        singleLine = false,
                    )
                }
            }
            item { KumaSectionHeader("Strategy") }
            item {
                StrategyChips(f.strategy, vm::onStrategy)
            }
            when (f.strategy) {
                MaintenanceEditViewModel.Strategy.MANUAL -> {
                    item {
                        ReadOnlyHint("// manual: pause/resume from the detail screen whenever you need.")
                    }
                }
                MaintenanceEditViewModel.Strategy.SINGLE -> {
                    item { KumaSectionHeader("When") }
                    item {
                        DateTimeFieldRow(
                            label = "start",
                            millis = f.startMillis,
                            onPickDate = { showStartDate = true },
                            onPickTime = { showStartTime = true },
                        )
                    }
                    item {
                        DateTimeFieldRow(
                            label = "end",
                            millis = f.endMillis,
                            onPickDate = { showEndDate = true },
                            onPickTime = { showEndTime = true },
                        )
                    }
                }
                MaintenanceEditViewModel.Strategy.RECURRING_WEEKDAY -> {
                    item { KumaSectionHeader("Weekdays") }
                    item { WeekdayChips(f.weekdays, vm::onWeekdayToggle) }
                    item { KumaSectionHeader("Time window") }
                    item {
                        TimeFieldRow("start", f.recurringStartMinuteOfDay) {
                            showStartTime = true
                        }
                    }
                    item {
                        TimeFieldRow("end", f.recurringEndMinuteOfDay) {
                            showEndTime = true
                        }
                    }
                }
                MaintenanceEditViewModel.Strategy.RECURRING_INTERVAL -> {
                    item { KumaSectionHeader("Every") }
                    item {
                        IntervalStepperRow(
                            value = f.intervalDay,
                            onChange = vm::onIntervalDay,
                        )
                    }
                    item { KumaSectionHeader("Time window") }
                    item {
                        TimeFieldRow("start", f.recurringStartMinuteOfDay) {
                            showStartTime = true
                        }
                    }
                    item {
                        TimeFieldRow("end", f.recurringEndMinuteOfDay) {
                            showEndTime = true
                        }
                    }
                }
                MaintenanceEditViewModel.Strategy.RECURRING_DAY_OF_MONTH -> {
                    item { KumaSectionHeader("Days of month") }
                    item { DayOfMonthGrid(f.daysOfMonth, vm::onDayOfMonthToggle) }
                    item { KumaSectionHeader("Time window") }
                    item {
                        TimeFieldRow("start", f.recurringStartMinuteOfDay) {
                            showStartTime = true
                        }
                    }
                    item {
                        TimeFieldRow("end", f.recurringEndMinuteOfDay) {
                            showEndTime = true
                        }
                    }
                }
                MaintenanceEditViewModel.Strategy.CRON -> {
                    item { KumaSectionHeader("Cron") }
                    item {
                        LabelledField("expression") {
                            KumaTextField(
                                value = f.cron,
                                onChange = vm::onCron,
                                placeholder = "30 3 * * *",
                            )
                        }
                    }
                    item {
                        ReadOnlyHint("// format: minute hour day-of-month month day-of-week. example: 30 3 * * * runs daily at 03:30.")
                    }
                    item { KumaSectionHeader("Duration") }
                    item {
                        IntervalStepperRow(
                            value = f.cronDurationMinutes,
                            onChange = vm::onCronDuration,
                            label = "minutes",
                            stepLarge = 30,
                        )
                    }
                }
            }
            item { KumaSectionHeader("Affected monitors") }
            item {
                AffectedMonitorsRow(
                    selectedCount = f.selectedMonitorIds.size,
                    totalCount = ui.monitorOptions.size,
                    onClick = { showMonitorPicker = true },
                )
            }
            item { KumaSectionHeader("Status") }
            item {
                ActiveToggleRow(active = f.active, onToggle = vm::onActive)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // --- Pickers ---
    if (showStartDate && f.strategy == MaintenanceEditViewModel.Strategy.SINGLE) {
        val state = rememberDatePickerState(initialSelectedDateMillis = f.startMillis)
        DatePickerDialog(
            onDismissRequest = { showStartDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date ->
                        // Combine with existing time, or default to current time of day.
                        val combined = combineDateAndTime(
                            date, f.startMillis ?: System.currentTimeMillis(),
                        )
                        vm.onStartMillis(combined)
                    }
                    showStartDate = false
                }) { Text("OK", color = KumaUp) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDate = false }) { Text("Cancel", color = KumaSlate2) }
            },
        ) { DatePicker(state = state) }
    }
    if (showEndDate && f.strategy == MaintenanceEditViewModel.Strategy.SINGLE) {
        val state = rememberDatePickerState(initialSelectedDateMillis = f.endMillis)
        DatePickerDialog(
            onDismissRequest = { showEndDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date ->
                        val combined = combineDateAndTime(
                            date, f.endMillis ?: System.currentTimeMillis(),
                        )
                        vm.onEndMillis(combined)
                    }
                    showEndDate = false
                }) { Text("OK", color = KumaUp) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDate = false }) { Text("Cancel", color = KumaSlate2) }
            },
        ) { DatePicker(state = state) }
    }
    if (showStartTime) {
        val initialMinuteOfDay = when (f.strategy) {
            MaintenanceEditViewModel.Strategy.SINGLE -> minuteOfDayOf(f.startMillis)
            else -> f.recurringStartMinuteOfDay
        }
        TimePickerDialog(
            initialMinuteOfDay = initialMinuteOfDay,
            onConfirm = { mod ->
                if (f.strategy == MaintenanceEditViewModel.Strategy.SINGLE) {
                    vm.onStartMillis(replaceTimeOfDay(f.startMillis, mod))
                } else {
                    vm.onRecurringStart(mod)
                }
                showStartTime = false
            },
            onDismiss = { showStartTime = false },
        )
    }
    if (showEndTime) {
        val initialMinuteOfDay = when (f.strategy) {
            MaintenanceEditViewModel.Strategy.SINGLE -> minuteOfDayOf(f.endMillis)
            else -> f.recurringEndMinuteOfDay
        }
        TimePickerDialog(
            initialMinuteOfDay = initialMinuteOfDay,
            onConfirm = { mod ->
                if (f.strategy == MaintenanceEditViewModel.Strategy.SINGLE) {
                    vm.onEndMillis(replaceTimeOfDay(f.endMillis, mod))
                } else {
                    vm.onRecurringEnd(mod)
                }
                showEndTime = false
            },
            onDismiss = { showEndTime = false },
        )
    }
    if (showMonitorPicker) {
        MonitorMultiSelectSheet(
            monitors = ui.monitorOptions,
            selectedIds = f.selectedMonitorIds,
            onToggle = vm::onMonitorToggle,
            onDismiss = { showMonitorPicker = false },
        )
    }
}

@Composable
private fun LabelledField(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label.uppercase(), color = KumaSlate2,
            fontFamily = KumaMono,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        content()
    }
}

@Composable
private fun KumaTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder?.let { { Text(it, color = KumaSlate2, fontFamily = KumaMono) } },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KumaCardCorner),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = KumaCardBorder,
            focusedBorderColor = KumaUp.copy(alpha = 0.6f),
            unfocusedContainerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            cursorColor = KumaUp,
            focusedTextColor = KumaInk,
            unfocusedTextColor = KumaInk,
        ),
    )
}

@Composable
private fun StrategyChips(
    selected: MaintenanceEditViewModel.Strategy,
    onSelect: (MaintenanceEditViewModel.Strategy) -> Unit,
) {
    val all = MaintenanceEditViewModel.Strategy.entries
    val rows = all.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { s ->
                    StrategyChip(
                        s, isSelected = s == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(s) },
                    )
                }
                // Pad short rows so chip widths stay even.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StrategyChip(
    s: MaintenanceEditViewModel.Strategy,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(KumaCardCorner),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) KumaUp else KumaCardBorder,
        ),
        onClick = onClick,
    ) {
        Box(
            Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.label.lowercase(),
                color = if (isSelected) KumaUp else KumaSlate2,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = KumaMono,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun WeekdayChips(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MaintenanceEditViewModel.WEEKDAY_NAMES.forEachIndexed { idx, label ->
            val isSel = idx in selected
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(KumaCardCorner),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSel) KumaUp else KumaCardBorder,
                ),
                onClick = { onToggle(idx) },
            ) {
                Box(
                    Modifier.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label.lowercase(),
                        color = if (isSel) KumaUp else KumaSlate2,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = KumaMono,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateTimeFieldRow(
    label: String,
    millis: Long?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Column {
        Text(label.uppercase(), color = KumaSlate2,
            fontFamily = KumaMono,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KumaCard(
                modifier = Modifier.weight(1f),
                onClick = onPickDate,
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        millis?.let { dateOnly(it) } ?: "—",
                        color = if (millis != null) KumaInk else KumaSlate2,
                        fontFamily = KumaMono,
                    )
                }
            }
            KumaCard(
                modifier = Modifier.width(110.dp),
                onClick = onPickTime,
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        millis?.let { timeOnly(it) } ?: "—",
                        color = if (millis != null) KumaInk else KumaSlate2,
                        fontFamily = KumaMono,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalStepperRow(
    value: Int,
    onChange: (Int) -> Unit,
    label: String = "days",
    stepLarge: Int = 7,
) {
    KumaCard {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(text = "−$stepLarge", onClick = { onChange(value - stepLarge) })
            StepButton(text = "−1", onClick = { onChange(value - 1) })
            Box(
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$value $label",
                    color = KumaInk,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = KumaMono,
                )
            }
            StepButton(text = "+1", onClick = { onChange(value + 1) })
            StepButton(text = "+$stepLarge", onClick = { onChange(value + stepLarge) })
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(KumaCardCorner),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, KumaUp.copy(alpha = 0.5f)),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = KumaUp, fontWeight = FontWeight.SemiBold,
                fontFamily = KumaMono)
        }
    }
}

@Composable
private fun DayOfMonthGrid(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val rows = (1..31).chunked(7)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { d ->
                    val isSel = d in selected
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(KumaCardCorner),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSel) KumaUp else KumaCardBorder,
                        ),
                        onClick = { onToggle(d) },
                    ) {
                        Box(
                            Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.toString(),
                                color = if (isSel) KumaUp else KumaSlate2,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = KumaMono,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TimeFieldRow(label: String, minuteOfDay: Int, onPick: () -> Unit) {
    Column {
        Text(label.uppercase(), color = KumaSlate2,
            fontFamily = KumaMono,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        KumaCard(onClick = onPick) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                val h = minuteOfDay / 60
                val m = minuteOfDay % 60
                Text("%02d:%02d".format(h, m), color = KumaInk,
                    fontFamily = KumaMono)
            }
        }
    }
}

@Composable
private fun AffectedMonitorsRow(
    selectedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
) {
    KumaCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (selectedCount == 0) "// no_monitors_selected"
                    else "selected=$selectedCount/$totalCount",
                    color = if (selectedCount == 0) KumaSlate2 else KumaInk,
                    fontFamily = KumaMono,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("tap to choose", color = KumaSlate2,
                    fontFamily = KumaMono,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text("[choose]", color = KumaUp, fontFamily = KumaMono,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActiveToggleRow(active: Boolean, onToggle: (Boolean) -> Unit) {
    KumaCard {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Active", color = KumaInk,
                    fontFamily = KumaFont, fontWeight = FontWeight.SemiBold)
                Text("// disable to create in paused state",
                    color = KumaSlate2, fontFamily = KumaMono,
                    style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = active,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = KumaUp,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = KumaSlate2.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

@Composable
private fun ReadOnlyHint(text: String) {
    KumaCard {
        Box(modifier = Modifier.padding(14.dp)) {
            Text(text, color = KumaSlate2, fontFamily = KumaMono,
                style = MaterialTheme.typography.labelSmall)
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
                Text("Pick a time", color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp))
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = KumaSlate2) }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text("OK", color = KumaUp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorMultiSelectSheet(
    monitors: List<Monitor>,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = KumaCream,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Affected Monitors",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", color = KumaUp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                items(monitors, key = { it.id }) { m ->
                    val isSel = m.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggle(m.id) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name,
                                color = if (isSel) KumaUp else KumaInk,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            val sub = m.hostname
                                ?: m.url?.takeIf { it.isNotBlank() && it != "https://" }
                            if (sub != null) {
                                Text(
                                    sub,
                                    color = if (isSel) KumaUp.copy(alpha = 0.6f) else KumaSlate2,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier.size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSel) KumaUp.copy(alpha = 0.18f) else KumaSurface
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = KumaUp,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// Locale-aware DISPLAY formatters — German users see `27.04.2026`, UK users
// `27/04/2026`, etc. Server payloads still go through the explicit `Locale.US`
// formatters in `MaintenanceEditViewModel` (see `formatServerDate`).
private fun dateOnly(ms: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(ms))

private fun timeOnly(ms: Long, context: android.content.Context? = null): String {
    val pattern = if (context != null && android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

private fun minuteOfDayOf(ms: Long?): Int {
    if (ms == null) return 0
    // Use java.time for tz-correct minute extraction (LZ7), consistent with
    // replaceTimeOfDay / combineDateAndTime which were rewritten in round 2.
    val zoned = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    return zoned.hour * 60 + zoned.minute
}

// `java.time` is used over `Calendar` so DST transitions resolve via the
// `ZonedDateTime` rules engine rather than silently shifting an hour. On
// "spring forward" days, asking for 02:30 in a TZ that skips that hour
// will land on 03:30 instead of returning a millis value that *looks* like
// 02:30 but is actually 03:30 due to the underlying Calendar's leniency.
private fun replaceTimeOfDay(ms: Long?, minuteOfDay: Int): Long {
    val instant = java.time.Instant.ofEpochMilli(ms ?: System.currentTimeMillis())
    val zoned = instant.atZone(java.time.ZoneId.systemDefault())
    return zoned
        .withHour(minuteOfDay / 60)
        .withMinute(minuteOfDay % 60)
        .withSecond(0)
        .withNano(0)
        .toInstant()
        .toEpochMilli()
}

private fun combineDateAndTime(dateMs: Long, timeSourceMs: Long): Long {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
    val time = java.time.Instant.ofEpochMilli(timeSourceMs).atZone(zone)
        .withSecond(0).withNano(0).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
