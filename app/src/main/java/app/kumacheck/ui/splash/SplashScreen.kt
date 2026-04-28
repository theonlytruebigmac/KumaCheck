package app.kumacheck.ui.splash

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMark
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaUp

@Composable
fun SplashScreen(
    vm: SplashViewModel,
    onToOverview: () -> Unit,
    onToLogin: () -> Unit,
) {
    val decision by vm.decision.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()

    LaunchedEffect(decision) {
        when (decision) {
            SplashViewModel.Decision.ToOverview -> onToOverview()
            SplashViewModel.Decision.ToLogin -> onToLogin()
            SplashViewModel.Decision.Loading -> Unit
        }
    }

    // UX5: phase label now tracks the actual socket handshake instead of
    // ticking on a 1.2s timer. The 8s `withTimeout` in SplashViewModel is
    // still the guard rail — this only influences what the user reads.
    val phaseLabel = when (connection) {
        app.kumacheck.data.socket.KumaSocket.Connection.DISCONNECTED -> "Reading prefs…"
        app.kumacheck.data.socket.KumaSocket.Connection.CONNECTING -> "Connecting…"
        app.kumacheck.data.socket.KumaSocket.Connection.CONNECTED -> "Authenticating…"
        app.kumacheck.data.socket.KumaSocket.Connection.LOGIN_REQUIRED -> "Signing in…"
        app.kumacheck.data.socket.KumaSocket.Connection.AUTHENTICATED -> "Ready"
        app.kumacheck.data.socket.KumaSocket.Connection.ERROR -> "Retrying…"
    }

    Box(
        modifier = Modifier.fillMaxSize().background(KumaCream),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KumaMark(
                size = 130.dp,
                pulse = true,
                ringColor = KumaUp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "KumaCheck",
                color = KumaInk,
                fontFamily = KumaFont,
                fontWeight = FontWeight.Bold,
                fontSize = KumaTypography.statNumber,
                letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "WATCHING, ALWAYS",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.caption,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(36.dp))
            Text(
                phaseLabel,
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = KumaTypography.caption,
            )
        }
    }
}
