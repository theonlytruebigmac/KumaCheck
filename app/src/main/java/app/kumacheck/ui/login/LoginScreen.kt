package app.kumacheck.ui.login

import app.kumacheck.ui.theme.KumaTypography

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kumacheck.ui.theme.KumaCardBorder
import app.kumacheck.ui.theme.KumaCream
import app.kumacheck.ui.theme.KumaDown
import app.kumacheck.ui.theme.KumaFont
import app.kumacheck.ui.theme.KumaInk
import app.kumacheck.ui.theme.KumaMark
import app.kumacheck.ui.theme.KumaMono
import app.kumacheck.ui.theme.KumaSlate2
import app.kumacheck.ui.theme.KumaSurface
import app.kumacheck.ui.theme.KumaTerra

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    vm: LoginViewModel,
    onAuthenticated: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.authenticated) {
        if (state.authenticated) onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KumaCream)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(
                    "← Back",
                    color = KumaSlate2,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KumaMark(size = 56.dp)
            Column {
                Text(
                    "WELCOME",
                    color = KumaSlate2,
                    fontFamily = KumaMono,
                    fontSize = KumaTypography.caption,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Sign in",
                    color = KumaInk,
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    letterSpacing = (-0.6).sp,
                )
            }
        }
        Text(
            "Connect to your Uptime Kuma server.",
            color = KumaSlate2,
            fontFamily = KumaFont,
            fontSize = KumaTypography.body,
        )
        Spacer(Modifier.height(8.dp))

        LoginField(
            value = state.serverUrl,
            onChange = vm::onServerUrl,
            label = "Server URL",
            placeholder = "https://kuma.example.com",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            enabled = !state.isWorking,
        )
        AnimatedVisibility(visible = isCleartextUrl(state.serverUrl)) {
            Text(
                "Cleartext (http://) — your password and session token will be sent unencrypted. OK on a trusted LAN; otherwise use https://.",
                color = KumaDown,
                fontFamily = KumaFont,
                fontSize = KumaTypography.captionLarge,
            )
        }
        LoginField(
            value = state.username,
            onChange = vm::onUsername,
            label = "Username",
            imeAction = ImeAction.Next,
            modifier = Modifier.semantics { contentType = ContentType.Username },
            enabled = !state.isWorking,
        )
        LoginField(
            value = state.password,
            onChange = vm::onPassword,
            label = "Password",
            keyboardType = KeyboardType.Password,
            imeAction = if (state.totpRequired) ImeAction.Next else ImeAction.Done,
            onDone = { keyboard?.hide(); vm.submit() },
            visualTransformation = if (passwordVisible)
                androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = KumaSlate2,
                    )
                }
            },
            modifier = Modifier.semantics { contentType = ContentType.Password },
            enabled = !state.isWorking,
        )

        AnimatedVisibility(visible = state.totpRequired) {
            LoginField(
                value = state.totp,
                onChange = vm::onTotp,
                label = "2FA code",
                placeholder = "123456",
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onDone = { keyboard?.hide(); vm.submit() },
                modifier = Modifier.semantics { contentType = ContentType.SmsOtpCode },
                enabled = !state.isWorking,
            )
        }

        state.error?.let {
            Text(
                it,
                color = KumaDown,
                fontFamily = KumaFont,
                fontSize = KumaTypography.captionLarge,
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = vm::submit,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KumaTerra,
                contentColor = Color.White,
            ),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    if (state.totpRequired) "Verify & sign in" else "Sign in",
                    fontFamily = KumaFont,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun isCleartextUrl(url: String): Boolean =
    url.trimStart().startsWith("http://", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = KumaFont) },
        placeholder = placeholder?.let { { Text(it, color = KumaSlate2, fontFamily = KumaMono, fontSize = KumaTypography.body) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = KumaCardBorder,
            focusedBorderColor = KumaTerra,
            unfocusedContainerColor = KumaSurface,
            focusedContainerColor = KumaSurface,
            cursorColor = KumaTerra,
            focusedTextColor = KumaInk,
            unfocusedTextColor = KumaInk,
            focusedLabelColor = KumaTerra,
            unfocusedLabelColor = KumaSlate2,
        ),
    )
}
