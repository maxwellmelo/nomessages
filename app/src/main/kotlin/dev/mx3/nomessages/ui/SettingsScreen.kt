package dev.mx3.nomessages.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.content.ClipDescription
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.mx3.nomessages.ui.icons.filled.ContentCopy
import dev.mx3.nomessages.ui.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mx3.nomessages.R
import dev.mx3.nomessages.core.vault.PasswordStrength
import dev.mx3.nomessages.core.vault.estimateStrength

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: UiState, actions: UiActions, onBack: () -> Unit) {
    val context = LocalContext.current
    // Resolved in composition so a configuration change invalidates it; reading it
    // from LocalContext inside the click lambda would serve a stale locale.
    val copiedNotice = stringResource(R.string.copied)
    var bridges by remember(state.bridges) { mutableStateOf(state.bridges) }
    var showPanic by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            bridges = ""
            showPanic = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        ) {
            NetworkBanner(state.network)
            SettingsSectionTitle(R.string.security)
            ListItem(
                headlineContent = { Text(stringResource(R.string.lock_now)) },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = actions::lock, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.lock_now))
            }
            Text(stringResource(R.string.lock_timeout), modifier = Modifier.padding(start = 16.dp, top = 20.dp), fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 15, 30).forEach { seconds ->
                    FilterChip(
                        selected = state.lockTimeoutSeconds == seconds,
                        onClick = { actions.setLockTimeout(seconds) },
                        label = { Text(timeoutLabel(seconds)) },
                    )
                }
            }
            Text(stringResource(R.string.keyboard_warning), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DoorbellSetting(enabled = state.doorbellEnabled, onChange = actions::setDoorbellEnabled)
            if (state.canChangePanicPassword) {
                OutlinedButton(onClick = { showPanic = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.change_panic_password))
                }
            }

            HorizontalDivider()
            SettingsSectionTitle(R.string.vault_transfer)
            Text(stringResource(R.string.export_detail), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.migration_safety), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions::exportVault, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text(stringResource(R.string.export_vault))
            }

            HorizontalDivider()
            SettingsSectionTitle(R.string.tor_identity)
            if (state.onion.isBlank()) {
                Text(stringResource(R.string.onion_unavailable), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(
                    value = state.onion,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        IconButton(onClick = {
                            copySensitive(context, state.onion)
                            Toast.makeText(context, copiedNotice, Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy)) }
                    },
                )
            }
            SettingsSectionTitle(R.string.bridges)
            Text(stringResource(R.string.bridges_detail), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrivateImeScope {
                OutlinedTextField(
                    value = bridges,
                    onValueChange = { bridges = it },
                    label = { Text(stringResource(R.string.bridges_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    keyboardOptions = privateKeyboardOptions(),
                )
            }
            Button(
                onClick = { actions.setBridges(bridges.trim()) },
                enabled = bridges != state.bridges,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.save)) }
        }
    }

    if (showPanic) {
        PanicPasswordDialog(
            onDismiss = { showPanic = false },
            onSubmit = { password, confirm ->
                showPanic = false
                actions.changePanicPassword(password, confirm)
            },
        )
    }
}

/**
 * The locked-state doorbell notice, inside the Security section right after the automatic-lock
 * controls: it describes what the app does *while locked*, so it belongs next to what decides when
 * the lock happens rather than in a section of its own.
 *
 * A [Switch] rather than the [androidx.compose.material3.Checkbox] used elsewhere in the app: the
 * checkboxes in `GroupWizardScreen`/`ChatScreen` pick items out of a list, while this is a single
 * binary preference that takes effect immediately - the Material 3 role for a switch. Colours are
 * left to the theme so the Graphite & Amber palette drives it like every other control here.
 *
 * Only one of the two descriptions is on screen at a time, matched to [enabled]: the setting is
 * self-explanatory only if it says what the *current* choice does. Both readings are stated as
 * plain facts and neither is presented as safer than the other (a design constraint, see
 * `docs/development/doorbell-design.md`), and the third line - true in both modes - stays visible so
 * the reader never has to flip the switch to learn what does not change.
 */
@Composable
private fun DoorbellSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.doorbell_setting_title),
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold,
        )
        Switch(checked = enabled, onCheckedChange = onChange)
    }
    Text(
        text = stringResource(if (enabled) R.string.doorbell_setting_on else R.string.doorbell_setting_off),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.doorbell_setting_context),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSectionTitle(resource: Int) {
    Text(
        text = stringResource(resource),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun timeoutLabel(seconds: Int): String = when (seconds) {
    60 -> stringResource(R.string.timeout_minute)
    in 120..Int.MAX_VALUE -> pluralStringResource(R.plurals.timeout_minutes, seconds / 60, seconds / 60)
    else -> pluralStringResource(R.plurals.timeout_seconds, seconds, seconds)
}

private fun copySensitive(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("", value)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
internal fun PasswordActionDialog(
    title: Int,
    detail: Int,
    field: Int,
    confirmLabel: Int,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    fun clear() { password = "" }
    DisposableEffect(Unit) { onDispose(::clear) }
    AlertDialog(
        onDismissRequest = {
            clear()
            onDismiss()
        },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(detail))
                Text(stringResource(R.string.migration_safety), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                PasswordField(password, { password = it }, field, true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = {
                    val chars = password.toCharArray()
                    clear()
                    try { onSubmit(chars) } finally { chars.fill('\u0000') }
                },
            ) { Text(stringResource(confirmLabel)) }
        },
        dismissButton = {
            TextButton(onClick = {
                clear()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Live [PasswordStrength] preview for [password], recomputed at most once per keystroke via
 * `remember(password)`. `estimateStrength` does not mutate or wipe its input, so the temporary
 * `CharArray` snapshot is owned by this call site and zero-filled in `finally`, matching the
 * discipline this dialog's `onSubmit` already uses for the arrays it sends to [UiActions].
 */
@Composable
private fun rememberPasswordStrength(password: String): PasswordStrength = remember(password) {
    val chars = password.toCharArray()
    try {
        estimateStrength(chars)
    } finally {
        chars.fill('\u0000')
    }
}

@Composable
private fun PanicPasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    fun clear() {
        password = ""
        confirm = ""
    }
    DisposableEffect(Unit) { onDispose(::clear) }
    // No real password is available at this UI layer (VaultManager.resetPanicPassword compares
    // against the real password's key material server-side, not plaintext -- see
    // docs/development/vault-api.md), so unlike SetupScreen this dialog can only preview strength
    // and confirm-match; it cannot do a live real/panic similarity check.
    val strength = rememberPasswordStrength(password)
    AlertDialog(
        onDismissRequest = {
            clear()
            onDismiss()
        },
        title = { Text(stringResource(R.string.change_panic_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.panic_reset_warning))
                PasswordField(password, { password = it }, R.string.new_panic_password, true)
                if (password.isNotEmpty()) {
                    PasswordStrengthMeter(strength, Modifier.padding(horizontal = 4.dp))
                    PasswordStrengthTips(strength, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                PasswordField(confirm, { confirm = it }, R.string.confirm_new_panic_password, true)
                PasswordMatchHint(
                    matches = if (confirm.isEmpty()) null else confirm == password,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && confirm.isNotEmpty() &&
                    strength.score >= 3 && confirm == password,
                onClick = {
                    val passwordChars = password.toCharArray()
                    val confirmChars = confirm.toCharArray()
                    clear()
                    try { onSubmit(passwordChars, confirmChars) } finally {
                        passwordChars.fill('\u0000')
                        confirmChars.fill('\u0000')
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                clear()
                onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
