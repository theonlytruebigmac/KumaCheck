package app.kumacheck.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream2
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaSurface
import app.kumacheck.ui.theme.KumaTerra

/**
 * Themed Material3 time picker dialog used everywhere KumaCheck asks the user
 * for a time-of-day. Single source of truth so the Settings (quiet hours) and
 * MaintenanceEdit (window start/end) pickers stay visually consistent.
 *
 * The vanilla `TimePicker` ships with M3-default purple selectors, a grey
 * clock face, and white-on-purple time chips that clash with the cozy
 * cream/terra palette. We wire `TimePickerDefaults.colors(...)` to the Kuma
 * tokens so it lands inside the theme rather than poking through it.
 *
 * The vanilla layout also packs everything left-aligned inside the surface;
 * we center title + picker so the visual axis matches the centered clock face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KumaTimePickerDialog(
    initialMinuteOfDay: Int,
    onConfirm: (minuteOfDay: Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pick a time",
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    val pickerColors = TimePickerDefaults.colors(
        clockDialColor = KumaCream2,
        clockDialSelectedContentColor = Color.White,
        clockDialUnselectedContentColor = KumaInk,
        selectorColor = KumaTerra,
        containerColor = KumaSurface,
        periodSelectorBorderColor = KumaCardBorder,
        periodSelectorSelectedContainerColor = KumaTerra,
        periodSelectorUnselectedContainerColor = KumaCream2,
        periodSelectorSelectedContentColor = Color.White,
        periodSelectorUnselectedContentColor = KumaInk,
        timeSelectorSelectedContainerColor = KumaTerra,
        timeSelectorUnselectedContainerColor = KumaCream2,
        timeSelectorSelectedContentColor = Color.White,
        timeSelectorUnselectedContentColor = KumaInk,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = KumaSurface,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TimePicker(state = state, colors = pickerColors)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = KumaSlate2, fontFamily = KumaFont)
                    }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text(
                            "OK",
                            color = KumaTerra,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = KumaFont,
                        )
                    }
                }
            }
        }
    }
}
