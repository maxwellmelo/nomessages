package dev.mx3.nomessages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp
import dev.mx3.nomessages.R
import dev.mx3.nomessages.core.vault.PasswordPolicy
import dev.mx3.nomessages.core.vault.PasswordStrength
import dev.mx3.nomessages.core.vault.estimateStrength

@Composable
internal fun SetupScreen(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var panicPassword by remember { mutableStateOf("") }
    var panicConfirm by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    fun clearSensitiveFields() {
        password = ""
        confirm = ""
        panicPassword = ""
        panicConfirm = ""
    }

    fun submit() {
        val mainChars = password.toCharArray()
        val confirmChars = confirm.toCharArray()
        val panicChars = panicPassword.toCharArray()
        val panicConfirmChars = panicConfirm.toCharArray()
        clearSensitiveFields()
        try {
            actions.setup(name.trim(), mainChars, confirmChars, panicChars, panicConfirmChars)
        } finally {
            mainChars.fill('\u0000')
            confirmChars.fill('\u0000')
            panicChars.fill('\u0000')
            panicConfirmChars.fill('\u0000')
        }
    }

    DisposableEffect(Unit) { onDispose(::clearSensitiveFields) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            if (thirdPartyImeActive) {
                Text(
                    stringResource(R.string.third_party_keyboard_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            PrivateImeScope {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.display_name)) },
                    keyboardOptions = privateKeyboardOptions(imeAction = ImeAction.Next),
                )
            }
            Spacer(Modifier.height(12.dp))
            PasswordField(password, { password = it }, R.string.main_password, !state.busy)
            val mainStrength = rememberPasswordStrength(password)
            if (password.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PasswordStrengthMeter(mainStrength, Modifier.padding(horizontal = 4.dp))
                PasswordStrengthTips(mainStrength, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            PasswordField(confirm, { confirm = it }, R.string.confirm_main_password, !state.busy)
            PasswordMatchHint(
                matches = if (confirm.isEmpty()) null else confirm == password,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(18.dp))
            PasswordField(panicPassword, { panicPassword = it }, R.string.panic_password, !state.busy)
            val panicStrength = rememberPasswordStrength(panicPassword)
            if (panicPassword.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PasswordStrengthMeter(panicStrength, Modifier.padding(horizontal = 4.dp))
                PasswordStrengthTips(panicStrength, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            // Real/panic similarity is checked only once both fields are non-blank; PasswordPolicy
            // throws distinct messages for exact equality vs. a trivial (substring/edit-distance<=2)
            // variation, which we map to distinct strings so the warning is specific.
            val panicSimilarity = rememberPanicSimilarityWarning(password, panicPassword)
            if (panicSimilarity != null) {
                Text(
                    text = panicSimilarity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            PasswordField(panicConfirm, { panicConfirm = it }, R.string.confirm_panic_password, !state.busy)
            PasswordMatchHint(
                matches = if (panicConfirm.isEmpty()) null else panicConfirm == panicPassword,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.password_requirement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            // Re-derived from state already computed above (mainStrength/panicStrength/panicSimilarity
            // each recompute at most once per keystroke via remember(...), not five times per field),
            // rather than five nested calls inlined into `enabled =`. The floor `>= 3` mirrors core's
            // PasswordPolicy.MINIMUM_ACCEPTABLE_SCORE, which is `internal` to the :core module and not
            // importable here, so it is intentionally duplicated as a literal (see SPEC.md §5).
            val canSubmit = name.isNotBlank() && password.isNotEmpty() && confirm.isNotEmpty() &&
                panicPassword.isNotEmpty() && panicConfirm.isNotEmpty() &&
                mainStrength.score >= 3 && confirm == password &&
                panicStrength.score >= 3 && panicConfirm == panicPassword &&
                panicSimilarity == null
            Button(
                onClick = ::submit,
                enabled = !state.busy && canSubmit,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.create_vault))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showImport = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.import_vault))
            }
        }
        if (state.busy) BusyOverlay()
    }
    if (showImport) {
        PasswordActionDialog(
            title = R.string.import_vault,
            detail = R.string.import_warning,
            field = R.string.import_password,
            confirmLabel = R.string.import_action,
            onDismiss = { showImport = false },
            onSubmit = {
                showImport = false
                actions.importVault(it)
            },
        )
    }
}

/**
 * Live [PasswordStrength] preview for [password], recomputed at most once per keystroke via
 * `remember(password)`. `estimateStrength` does not mutate or wipe its input, so the temporary
 * `CharArray` snapshot is owned by this call site and zero-filled in `finally`, matching the
 * discipline `submit()` already uses for the arrays it sends to [UiActions].
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

/**
 * Live real/panic similarity warning, `null` until both passwords are non-blank. Calls
 * [PasswordPolicy.validatePair] purely for its side of throwing `IllegalArgumentException` with a
 * distinguishing message; the temporary `CharArray` snapshots are zero-filled in `finally`, same
 * discipline as [rememberPasswordStrength] and `submit()`.
 */
@Composable
private fun rememberPanicSimilarityWarning(password: String, panicPassword: String): String? {
    if (password.isBlank() || panicPassword.isBlank()) return null
    val mustDiffer = stringResource(R.string.panic_password_must_differ)
    val tooSimilar = stringResource(R.string.panic_password_too_similar)
    return remember(password, panicPassword) {
        val realChars = password.toCharArray()
        val panicChars = panicPassword.toCharArray()
        try {
            PasswordPolicy().validatePair(realChars, panicChars)
            null
        } catch (error: IllegalArgumentException) {
            when (error.message) {
                "Passwords must be distinct" -> mustDiffer
                "Panic password is too similar to the real password" -> tooSimilar
                else -> null
            }
        } finally {
            realChars.fill('\u0000')
            panicChars.fill('\u0000')
        }
    }
}

@Composable
internal fun LockScreen(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    var password by remember { mutableStateOf("") }

    fun clearPassword() {
        password = ""
    }

    fun submit() {
        if (password.isEmpty()) return
        val chars = password.toCharArray()
        clearPassword()
        try {
            actions.unlock(chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    DisposableEffect(Unit) { onDispose(::clearPassword) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.unlock_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(22.dp))
            if (thirdPartyImeActive) {
                Text(
                    stringResource(R.string.third_party_keyboard_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = R.string.password,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.no_password_recovery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = ::submit,
                enabled = password.isNotEmpty() && !state.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.unlock))
            }
        }
        if (state.busy) BusyOverlay()
    }
}
