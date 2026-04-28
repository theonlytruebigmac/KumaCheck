package app.kumacheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.kumacheck.data.auth.ThemeMode
import app.kumacheck.notify.Notifications
import app.kumacheck.ui.AppNav
import app.kumacheck.ui.common.ProvideTickingNow
import app.kumacheck.ui.theme.KumaTheme
import app.kumacheck.ui.theme.PulseAlphaProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Set when the user opens MainActivity from a notification with a monitor_id
     * extra. AppNav observes this and navigates to the monitor detail when it
     * appears.
     */
    var pendingMonitorId by mutableStateOf<Int?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as KumaCheckApp
        // Wait for the disk-backed nonce store to finish hydrating before
        // consuming the intent's deep-link extras (PD1). On a process-death
        // restore, the in-memory store is empty; without the await the
        // consume would silently fail and the user lands on Overview
        // instead of the monitor they tapped.
        lifecycleScope.launch {
            app.noncesReady.await()
            readMonitorIdFrom(intent)
        }
        setContent {
            val mode by app.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val useDark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }
            KumaTheme(useDark = useDark) {
                // Flip status-/nav-bar icon mode (light icons on dark bg, dark
                // on light). Bar backgrounds are transparent under edgeToEdge —
                // the cream Surface below shows through. Setting
                // window.statusBarColor / navigationBarColor is deprecated as
                // of API 35 and is a no-op on Android 15+.
                val view = LocalView.current
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !useDark
                    controller.isAppearanceLightNavigationBars = !useDark
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProvideTickingNow {
                        // Single shared pulse-alpha state for every
                        // StatusDot in the tree — see Kuma.kt's
                        // [LocalPulseAlpha]. Pre-fix, every UP row in a
                        // 60+ monitor list ran its own infinite alpha
                        // animation, dominating the main thread during
                        // scroll.
                        PulseAlphaProvider {
                            AppNav(
                                app = app,
                                pendingMonitorId = { pendingMonitorId },
                                onConsumeMonitorId = { pendingMonitorId = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as KumaCheckApp
        lifecycleScope.launch {
            app.noncesReady.await()
            readMonitorIdFrom(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-ensure the monitoring service is running. Covers the Android 15
        // `dataSync` 6h cap case: the system stopped MonitorService while
        // backgrounded, and the in-process prefs collector won't re-emit
        // because the underlying state didn't change. Foreground entry is
        // our retry hook. ContextCompat.startForegroundService is a no-op
        // when the service is already running, so this is safe to call always.
        val app = application as KumaCheckApp
        lifecycleScope.launch {
            val enabled = app.prefs.notificationsEnabledOnce()
            val token = app.prefs.tokenOnce()
            if (enabled && !token.isNullOrBlank()) {
                app.startMonitorServiceSafely()
            }
        }
    }

    private fun readMonitorIdFrom(intent: Intent?) {
        val id = intent?.getIntExtra(Notifications.EXTRA_MONITOR_ID, -1) ?: -1
        if (id < 0) return
        val nonce = intent?.getStringExtra(Notifications.EXTRA_DEEP_LINK_NONCE)
        // Strip the extras up front so a config change can't re-trigger
        // navigation, and a rejected forged intent doesn't linger.
        intent?.removeExtra(Notifications.EXTRA_MONITOR_ID)
        intent?.removeExtra(Notifications.EXTRA_DEEP_LINK_NONCE)
        if (Notifications.consumeDeepLinkNonce(nonce, id)) {
            pendingMonitorId = id
        }
    }
}
