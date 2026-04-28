package app.kumacheck.ui.statuspages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.data.model.StatusPage
import app.kumacheck.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusPagesListScreen(
    vm: StatusPagesListViewModel,
    onBack: () -> Unit,
    onPageTap: (String) -> Unit,
) {
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = KumaCream,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Status pages",
                        fontFamily = KumaFont,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KumaCream,
                    titleContentColor = KumaInk,
                    navigationIconContentColor = KumaInk,
                    actionIconContentColor = KumaInk,
                ),
            )
        },
    ) { padding ->
        when {
            ui.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text("Loading…", color = KumaSlate2, fontFamily = KumaFont) }
            ui.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // UX2: surface the error with a Retry affordance instead of
                // forcing the user to navigate away and back.
                app.kumacheck.ui.common.ErrorRetryRow(
                    message = ui.error!!,
                    onRetry = vm::refresh,
                )
            }
            ui.pages.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No status pages configured",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(ui.pages, key = { it.id }) { page ->
                    StatusPageRow(page, onClick = { onPageTap(page.slug) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatusPageRow(page: StatusPage, onClick: () -> Unit) {
    KumaCard(onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(KumaUp.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = KumaUp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.title,
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = KumaTypography.bodyEmphasis,
                    maxLines = 1,
                )
                Text(
                    "/${page.slug}",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                )
            }
            if (!page.published) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = KumaWarn.copy(alpha = 0.14f),
                ) {
                    Text(
                        "DRAFT",
                        color = KumaWarn,
                        fontFamily = KumaMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = KumaSlate2,
            )
        }
    }
}
