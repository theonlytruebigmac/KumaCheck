package app.kumacheck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C4 smoke test. Confirms the instrumented test path actually wires up —
 * the AndroidJUnitRunner can resolve the app package and the target
 * context survives instantiation. A failure here means the C4 scaffolding
 * itself is broken (build config, manifest merge, runner class missing),
 * not a regression in any feature.
 *
 * Real instrumented tests should live alongside this file with feature-
 * scoped names (e.g. `LoginFlowSmokeTest`, `OverviewWidgetTest`).
 */
@RunWith(AndroidJUnit4::class)
class PackageContextSmokeTest {

    @Test fun targetContext_isApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.kumacheck", ctx.packageName)
    }

    @Test fun applicationClass_isKumaCheckApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(KumaCheckApp::class.java, ctx.applicationContext.javaClass)
    }
}
