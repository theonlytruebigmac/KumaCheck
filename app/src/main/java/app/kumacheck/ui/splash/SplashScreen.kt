package app.kumacheck.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val decision by vm.decision.collectAsState()

    LaunchedEffect(decision) {
        when (decision) {
            SplashViewModel.Decision.ToOverview -> onToOverview()
            SplashViewModel.Decision.ToLogin -> onToLogin()
            SplashViewModel.Decision.Loading -> Unit
        }
    }

    val phases = remember {
        listOf(
            "Reading prefs…",
            "Connecting socket…",
            "Authenticating…",
        )
    }
    var phaseIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (phaseIndex < phases.lastIndex) {
            kotlinx.coroutines.delay(1_200)
            phaseIndex++
        }
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
                fontSize = 24.sp,
                letterSpacing = (-0.6).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "WATCHING, ALWAYS",
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(36.dp))
            Text(
                phases[phaseIndex],
                color = KumaSlate2,
                fontFamily = KumaMono,
                fontSize = 11.sp,
            )
        }
    }
}
