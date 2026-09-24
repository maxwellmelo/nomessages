package dev.mx3.nomessages.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Centralized helpers that reduce keyboard/IME data leakage for every text field in the app.
 *
 * Two independent levers are combined, because neither alone stops both leak paths:
 *
 * 1. [privateKeyboardOptions] turns off Compose's own autocorrect flag on [KeyboardOptions]
 *    (`autoCorrectEnabled = false`). This only affects Compose-side autocorrect UI/state; it does
 *    NOT stop the platform IME (e.g. Gboard) from learning the typed words or from applying its
 *    own suggestion/personalization engine, which is a platform-level concern handled by (2).
 *
 * 2. [PrivateImeScope] wraps a subtree (typically one text field) and intercepts the platform
 *    [EditorInfo] built when the IME session starts, ORing in:
 *      - `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` (imeOptions) — tells the system IME not to
 *        add whatever is typed to its personalized-learning/personal dictionary.
 *      - `InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS` (inputType) — tells the IME not to offer
 *        dictionary-based suggestions for this field.
 *    Both bits are OR'd onto the EditorInfo the field itself already produced, so nothing the
 *    field already set (input class, password variation, multi-line flag, existing imeOptions
 *    such as IME_ACTION_SEND) is lost.
 *
 * ## Why a Composable wrapper (`PrivateImeScope`) instead of a `Modifier` extension
 *
 * The task that motivated this file asked for a `Modifier.privateIme()`. After inspecting the
 * resolved Compose UI API for this project (compose-bom 2026.06.01 -> androidx.compose.ui:ui
 * 1.11.4), the only API that can intercept the platform `EditorInfo`/`InputConnection` is
 * `InterceptPlatformTextInput`, which has this shape:
 *
 * ```
 * @ExperimentalComposeUiApi
 * @Composable
 * fun InterceptPlatformTextInput(
 *     interceptor: PlatformTextInputInterceptor,
 *     content: @Composable () -> Unit,
 * )
 * ```
 *
 * It works by providing a `CompositionLocal` that is consulted when a descendant field later
 * establishes its text-input session. A `CompositionLocal` can only be provided to a subtree via
 * a `content: @Composable () -> Unit` lambda — a `Modifier` extension function has no such lambda
 * to offer; it can only contribute to the `Modifier` chain of the single node it is attached to,
 * so it structurally cannot wrap "the rest of the composition below this point" the way
 * `InterceptPlatformTextInput` requires. Because of that, this file exposes the equivalent
 * capability as the composable [PrivateImeScope] and every call site wraps its text field with it
 * (`PrivateImeScope { OutlinedTextField(...) }`) instead of chaining a modifier.
 *
 * We also evaluated `KeyboardOptions.platformImeOptions` / `PlatformImeOptions` (also new in this
 * Compose UI version) as a simpler alternative: it only forwards a `privateImeOptions` *string*
 * extra to the IME (an opt-in hint many IMEs ignore) and has no typed way to OR bits into
 * `EditorInfo.imeOptions` or `inputType`. It cannot deliver `IME_FLAG_NO_PERSONALIZED_LEARNING` or
 * `TYPE_TEXT_FLAG_NO_SUGGESTIONS` reliably, so `InterceptPlatformTextInput` remains the only choice
 * for those two flags and is used exclusively for that purpose.
 */

/**
 * [KeyboardOptions] with autocorrect disabled by default, so callers get private-by-default
 * behavior while still choosing the [keyboardType] (e.g. [KeyboardType.Password]) and [imeAction]
 * their field needs.
 */
fun privateKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions = KeyboardOptions(
    autoCorrectEnabled = false,
    keyboardType = keyboardType,
    imeAction = imeAction,
)

/**
 * Wraps [content] (normally a single text field) so that every IME session it starts has
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] and [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS]
 * OR'd onto whatever [EditorInfo] the field itself builds. See the file-level KDoc above for why
 * this is a composable wrapper rather than a `Modifier` extension, and why it is combined with
 * [privateKeyboardOptions] rather than replacing it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateImeScope(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(
                PlatformTextInputMethodRequest { outAttrs ->
                    // Let the field build its own EditorInfo/InputConnection first, then OR our
                    // privacy flags on top so we never drop bits the field itself depends on
                    // (e.g. TYPE_TEXT_VARIATION_PASSWORD or IME_ACTION_SEND).
                    val connection = request.createInputConnection(outAttrs)
                    outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    connection
                },
            )
        },
        content = content,
    )
}
