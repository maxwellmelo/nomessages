package dev.mx3.nomessages.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import dev.mx3.nomessages.ui.icons.filled.CloudDone
import dev.mx3.nomessages.ui.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mx3.nomessages.R
import dev.mx3.nomessages.core.vault.PasswordFeedback
import dev.mx3.nomessages.core.vault.PasswordStrength

@Composable
internal fun BrandMark(modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(
        modifier = modifier
            .size(if (compact) 42.dp else 76.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "NM",
            color = Color.White,
            fontSize = if (compact) 18.sp else 30.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Password fields must never trigger IME suggestions or personalized learning: combine
    // KeyboardType.Password (already used here) with the private IME flags from PrivateInput.kt.
    PrivateImeScope {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(text = androidx.compose.ui.res.stringResource(label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = privateKeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
}

/** Segment count of [PasswordStrengthMeter]'s bar; also the ceiling [PasswordStrength.score] is capped to. */
private const val STRENGTH_METER_SEGMENTS = 4

/** Live strength label for a bucketed [PasswordStrength.score], per the {0,1}/{2}/{3}/{4} ladder in the spec. */
@Composable
private fun strengthLabel(score: Int): String = when {
    score <= 1 -> stringResource(R.string.password_strength_weak)
    score == 2 -> stringResource(R.string.password_strength_fair)
    score == 3 -> stringResource(R.string.password_strength_good)
    else -> stringResource(R.string.password_strength_strong)
}

/** Bar color for a bucketed [PasswordStrength.score]; mirrors [strengthLabel]'s bucketing. */
@Composable
private fun strengthColor(score: Int): Color = when {
    score <= 1 -> MaterialTheme.colorScheme.error
    score == 2 -> Color(0xFFB8860B) // amber; MaterialTheme has no "fair/warning" role to reuse here.
    score == 3 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.secondary
}

/**
 * Four-segment live strength bar plus a text label, driven directly by [strength] on every
 * recomposition (typically once per keystroke — [dev.mx3.nomessages.core.vault.estimateStrength]
 * is cheap and local, so no debouncing is needed). Segments filled = `score` (0..4), capped to
 * [STRENGTH_METER_SEGMENTS] — a score of 4 still only fills all four segments.
 */
@Composable
internal fun PasswordStrengthMeter(strength: PasswordStrength, modifier: Modifier = Modifier) {
    val filled = strength.score.coerceIn(0, STRENGTH_METER_SEGMENTS)
    val color = strengthColor(strength.score)
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            for (segment in 0 until STRENGTH_METER_SEGMENTS) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (segment < filled) color else trackColor),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = strengthLabel(strength.score),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Maps a closed [PasswordFeedback] key to a short localized tip. `LOOKS_STRONG` is deliberately
 * not surfaced here — the strong-colored bar and "Forte"/"Strong" label already convey that state
 * — and any future key this file does not yet know about is silently skipped via the `else`
 * branch rather than crashing.
 */
@Composable
private fun feedbackHint(key: String): String? = when (key) {
    PasswordFeedback.TOO_SHORT, PasswordFeedback.ADD_LENGTH -> stringResource(R.string.password_hint_too_short)
    PasswordFeedback.SEQUENTIAL_CHARS -> stringResource(R.string.password_hint_sequential)
    PasswordFeedback.REPEATED_CHARS -> stringResource(R.string.password_hint_repeated)
    PasswordFeedback.COMMON_WORD -> stringResource(R.string.password_hint_common_word)
    PasswordFeedback.DATE_OR_YEAR -> stringResource(R.string.password_hint_date_or_year)
    PasswordFeedback.TRIVIAL_SUFFIX_PATTERN -> stringResource(R.string.password_hint_trivial_suffix)
    PasswordFeedback.ADD_VARIETY -> stringResource(R.string.password_hint_add_variety)
    else -> null
}

/** Up to two short, actionable tips drawn from [strength]'s feedback keys. */
@Composable
internal fun PasswordStrengthTips(strength: PasswordStrength, modifier: Modifier = Modifier) {
    val tips = strength.feedback.mapNotNull { feedbackHint(it) }.distinct().take(2)
    if (tips.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        tips.forEach { tip ->
            Text(
                text = tip,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Live confirm-match indicator. `null` (confirm field still empty) renders nothing; `false`
 * (diverges) and `true` (matches) render a short colored line, following this file's existing
 * convention of small `Text` rows instead of a new icon dependency.
 */
@Composable
internal fun PasswordMatchHint(matches: Boolean?, modifier: Modifier = Modifier) {
    if (matches == null) return
    Text(
        text = if (matches) stringResource(R.string.password_match) else stringResource(R.string.password_mismatch),
        style = MaterialTheme.typography.labelSmall,
        color = if (matches) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun Avatar(label: String, modifier: Modifier = Modifier, group: Boolean = false) {
    val initials = label.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { if (group) "G" else "?" }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (group) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun NetworkBanner(status: NetworkStatus, compact: Boolean = false) {
    val (message, online) = when (status) {
        NetworkStatus.OFF -> R.string.network_off to false
        NetworkStatus.STARTING -> R.string.network_starting to false
        NetworkStatus.PUBLISHING -> R.string.network_publishing to false
        NetworkStatus.ONLINE -> R.string.network_online to true
        NetworkStatus.RETRYING -> R.string.network_retrying to false
        NetworkStatus.ERROR -> R.string.network_error to false
    }
    Surface(
        color = if (online) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (online) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = androidx.compose.ui.res.stringResource(message),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun EmptyState(
    @StringRes title: Int,
    @StringRes detail: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark()
        Spacer(Modifier.height(22.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun BusyOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 8.dp) {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(androidx.compose.ui.res.stringResource(R.string.busy))
            }
        }
    }
}

@Composable
internal fun StateMessageDialogs(
    error: String?,
    notice: String?,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.error_title)) },
            text = { Text(error) },
            confirmButton = {
                Button(onClick = onDismissError) { Text(androidx.compose.ui.res.stringResource(R.string.dismiss)) }
            },
        )
    }
    if (error == null && notice != null) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            title = { Text(androidx.compose.ui.res.stringResource(R.string.notice)) },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) { Text(androidx.compose.ui.res.stringResource(R.string.dismiss)) }
            },
        )
    }
}
