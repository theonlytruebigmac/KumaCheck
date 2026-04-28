package app.kumacheck.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream2
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSectionHeader
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaTypography

/**
 * Q4: extracted from [MonitorEditScreen.typeSpecificFields] — the
 * `when (ui.form.authMethod) { ... }` block alone is ~120 lines and
 * touches a different set of form fields per branch (basic / NTLM /
 * OAuth2 / mTLS / unknown). Pulling it into its own LazyListScope
 * extension keeps the parent function readable and gives each
 * auth-method block a named call site.
 *
 * Same pattern: any future auth method gets a new `when` branch here
 * rather than another nested chunk in the parent.
 */
internal fun LazyListScope.authMethodFields(
    ui: MonitorEditViewModel.UiState,
    vm: MonitorEditViewModel,
) {
    when (ui.form.authMethod) {
        "basic" -> {
            item { KumaSectionHeader("Username") }
            item {
                KumaInputField(
                    value = ui.form.basicAuthUser,
                    onChange = vm::onBasicAuthUser,
                    placeholder = "user",
                )
            }
            item { KumaSectionHeader("Password") }
            item {
                KumaInputField(
                    value = ui.form.basicAuthPass,
                    onChange = vm::onBasicAuthPass,
                    placeholder = "••••••••",
                    password = true,
                )
            }
        }
        "ntlm" -> {
            item { KumaSectionHeader("Username") }
            item {
                KumaInputField(
                    value = ui.form.basicAuthUser,
                    onChange = vm::onBasicAuthUser,
                    placeholder = "user",
                )
            }
            item { KumaSectionHeader("Password") }
            item {
                KumaInputField(
                    value = ui.form.basicAuthPass,
                    onChange = vm::onBasicAuthPass,
                    placeholder = "••••••••",
                    password = true,
                )
            }
            item { KumaSectionHeader("Domain") }
            item {
                KumaInputField(
                    value = ui.form.authDomain,
                    onChange = vm::onAuthDomain,
                    placeholder = "EXAMPLE",
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Workstation (optional)") }
            item {
                KumaInputField(
                    value = ui.form.authWorkstation,
                    onChange = vm::onAuthWorkstation,
                    monospace = true,
                )
            }
        }
        "oauth2-cc" -> {
            item { KumaSectionHeader("Token URL") }
            item {
                KumaInputField(
                    value = ui.form.oauthTokenUrl,
                    onChange = vm::onOauthTokenUrl,
                    placeholder = "https://auth.example.com/oauth2/token",
                    keyboardType = KeyboardType.Uri,
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Client ID") }
            item {
                KumaInputField(
                    value = ui.form.oauthClientId,
                    onChange = vm::onOauthClientId,
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Client secret") }
            item {
                KumaInputField(
                    value = ui.form.oauthClientSecret,
                    onChange = vm::onOauthClientSecret,
                    password = true,
                )
            }
            item { KumaSectionHeader("Scopes (optional)") }
            item {
                KumaInputField(
                    value = ui.form.oauthScopes,
                    onChange = vm::onOauthScopes,
                    placeholder = "read write",
                    monospace = true,
                )
            }
            item { KumaSectionHeader("Token auth method") }
            item {
                KumaInputField(
                    value = ui.form.oauthAuthMethod,
                    onChange = vm::onOauthAuthMethod,
                    placeholder = "client_secret_basic",
                    monospace = true,
                )
            }
        }
        "mtls" -> {
            item { KumaSectionHeader("CA certificate (PEM)") }
            item {
                KumaInputField(
                    value = ui.form.tlsCa, onChange = vm::onTlsCa,
                    placeholder = "-----BEGIN CERTIFICATE-----…",
                    monospace = true, singleLine = false,
                )
            }
            item { KumaSectionHeader("Client certificate (PEM)") }
            item {
                KumaInputField(
                    value = ui.form.tlsCert, onChange = vm::onTlsCert,
                    placeholder = "-----BEGIN CERTIFICATE-----…",
                    monospace = true, singleLine = false,
                )
            }
            item { KumaSectionHeader("Client private key (PEM)") }
            item {
                KumaInputField(
                    value = ui.form.tlsKey, onChange = vm::onTlsKey,
                    placeholder = "-----BEGIN PRIVATE KEY-----…",
                    monospace = true, singleLine = false, password = true,
                )
            }
        }
        "" -> Unit  // No auth — nothing to render.
        else -> {
            // Unknown future method — round-trip via raw, surface as read-only.
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KumaCream2,
                    border = BorderStroke(1.dp, KumaCardBorder),
                ) {
                    Text(
                        "${ui.form.authMethod.uppercase()} configured — edit in the Kuma web UI",
                        color = KumaSlate2,
                        fontFamily = KumaMono,
                        fontSize = KumaTypography.caption,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    // Visual breathing room before the next section the parent renders.
    item { Spacer(Modifier.height(0.dp)) }
}
