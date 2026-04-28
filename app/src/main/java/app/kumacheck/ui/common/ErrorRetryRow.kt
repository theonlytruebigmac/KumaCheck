package app.kumacheck.ui.common

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.ui.theme.KumaCardCorner
import app.kumacheck.ui.theme.KumaDown
import app.kumacheck.ui.theme.KumaDownBg
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaSlate

/**
 * UX2: standardised error + retry row. Use whenever a screen renders an
 * error string that the user could conceivably resolve by trying again
 * (network drop, server timeout, transient socket error). Pulled into a
 * shared composable so error states across StatusPages, MonitorDetail,
 * and IncidentDetail look and behave identically — including offering
 * a "Retry" affordance instead of forcing a navigate-away-and-back.
 */
@Composable
fun ErrorRetryRow(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry",
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KumaCardCorner))
            .background(KumaDownBg)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Couldn't load",
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = KumaTypography.bodyEmphasis,
            )
            Text(
                message,
                color = KumaSlate,
                fontFamily = KumaFont,
                fontSize = KumaTypography.captionLarge,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onRetry,
                border = BorderStroke(1.dp, KumaDown),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    retryLabel,
                    color = KumaDown,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = KumaTypography.body,
                )
            }
        }
    }
}
