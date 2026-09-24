package dev.mx3.nomessages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mx3.nomessages.runtime.CameraCaptureScreen
import dev.mx3.nomessages.runtime.PlatformActions
import dev.mx3.nomessages.runtime.NoMessagesController
import dev.mx3.nomessages.ui.NoMessagesApp
import dev.mx3.nomessages.ui.NoMessagesTheme
import dev.mx3.nomessages.ui.isSystemIme
import dev.mx3.nomessages.ui.shouldApplySecureFlag

class MainActivity : ComponentActivity(), PlatformActions {
    private val controller: NoMessagesController get() = (application as NoMessagesApplication).controller
    private var resultOwner = 0L
    private val export = registerForActivityResult(LocalVaultExport()) { controller.acceptExport(resultOwner, it) }
    private val importLauncher = registerForActivityResult(LocalOpenDocument()) { controller.acceptImport(resultOwner, it) }
    private val attachment = registerForActivityResult(LocalOpenDocument()) { controller.acceptAttachment(resultOwner, it) }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.microphonePermission(resultOwner, it) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var notificationAsked = false

    // Compose-observable platform signals, updated from the non-Compose activity lifecycle below
    // and read inside setContent's composition so a change (a new default IME, a detected capture
    // attempt) recomposes without any extra plumbing through UiState/NoMessagesController.
    private val thirdPartyImeActive = mutableStateOf(false)
    private val captureAlertToken = mutableIntStateOf(0)

    // API 34+ only; null on lower API levels and while not registered. See onStart/onStop.
    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (shouldApplySecureFlag(BuildConfig.DEBUG, debugCaptureProperty())) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        // Autofill hardening: the vault's passwords/messages must never be offered to (or read by)
        // an autofill service. NO_EXCLUDE_DESCENDANTS also forces every descendant view - including
        // the whole Compose hierarchy hosted below this decorView - out of the autofill tree, not
        // just this one view, which is why it is set here once rather than per-field.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Accessibility hardening (API 34+): tells the platform this window's content is sensitive,
        // so a non-default, third-party AccessibilityService (not the user's chosen screen reader -
        // see the trade-off note in docs/security-model.md) does not receive its AccessibilityNodeInfo
        // tree. Set on the decorView - the root of the whole window, above the Compose hierarchy -
        // so it applies to every descendant by inheritance instead of needing to be threaded through
        // each screen. Deliberately NOT a global importantForAccessibility = NO: that removes nodes
        // from the tree entirely (breaking legitimate screen readers and this app's own Compose UI
        // tests), where ACCESSIBILITY_DATA_SENSITIVE_YES only withholds content from a *non-default*
        // service while leaving the tree itself intact.
        if (Build.VERSION.SDK_INT >= 34) {
            window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        resultOwner = controller.attachPlatform(this, savedInstanceState?.takeIf { it.containsKey(RESULT_OWNER) }?.getLong(RESULT_OWNER))
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.unlocked) {
                if (state.unlocked && Build.VERSION.SDK_INT >= 33 && !notificationAsked) {
                    notificationAsked = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val request by controller.cameraRequest.collectAsStateWithLifecycle()
            val captureId = request
            val imeThirdParty by thirdPartyImeActive
            val snackbarHostState = remember { SnackbarHostState() }
            val captureDetectedMessage = stringResource(R.string.screen_capture_detected)
            // FLAG_SECURE already blocks the capture itself (see shouldApplySecureFlag); this only
            // shows a non-blocking notice for whenever someone still attempts it (API 34+).
            LaunchedEffect(captureAlertToken.intValue) {
                if (captureAlertToken.intValue > 0) snackbarHostState.showSnackbar(captureDetectedMessage)
            }
            Box(Modifier.fillMaxSize()) {
                if (state.unlocked && captureId != null) key(captureId) {
                    NoMessagesTheme { CameraCaptureScreen({ bytes -> controller.acceptPhoto(captureId, bytes) }, controller::cancelPhoto) }
                }
                else NoMessagesApp(state, controller, thirdPartyImeActive = imeThirdParty)
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    override fun onStart() {
        super.onStart()
        controller.onForeground()
        thirdPartyImeActive.value = computeThirdPartyImeActive(this)
        if (Build.VERSION.SDK_INT >= 34 && screenCaptureCallback == null) {
            val callback = Activity.ScreenCaptureCallback { captureAlertToken.intValue += 1 }
            screenCaptureCallback = callback
            registerScreenCaptureCallback(mainExecutor, callback)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(RESULT_OWNER, resultOwner)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 34) {
            screenCaptureCallback?.let { unregisterScreenCaptureCallback(it) }
            screenCaptureCallback = null
        }
        if (isFinishing) controller.lock() else controller.onBackground()
        super.onStop()
    }
    override fun onDestroy() {
        controller.detachPlatform(this, isFinishing)
        super.onDestroy()
    }
    override fun chooseExport() = export.launch("nomessages-vault-${System.currentTimeMillis()}.bin")
    override fun chooseImport() = importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
    override fun chooseAttachment() = attachment.launch(arrayOf("*/*"))
    override fun requestMicrophone() = microphone.launch(Manifest.permission.RECORD_AUDIO)
    companion object { private const val RESULT_OWNER = "nomessages.result_owner" }
}

/**
 * Whether the system's current default IME belongs to a third-party app rather than one shipped
 * (or updated in place, see [isSystemApplicationInfo]) as part of the OS image. Android gives an
 * app no way to sandbox what an IME can read per-app, so when this is true, every field in the app
 * - even ones hardened by [dev.mx3.nomessages.ui.PrivateImeScope] - is still visible to that
 * keyboard while it is composing input; the banner this drives (see
 * [dev.mx3.nomessages.ui.NoMessagesApp]) is a disclosure, not a mitigation.
 *
 * Recomputed on every `onStart` (see [MainActivity.onStart]): switching the default keyboard while
 * NoMessages is backgrounded is picked up the next time it is foregrounded. Switching it without
 * leaving NoMessages (e.g. via the IME picker notification while a field is already focused) is
 * not observed until the next `onStart`.
 *
 * Deliberately reads each enabled IME's `ApplicationInfo` off the `InputMethodInfo` itself
 * (`InputMethodInfo.serviceInfo.applicationInfo`) rather than issuing a fresh
 * `PackageManager.getApplicationInfo(packageName, ...)` call: the latter is subject to Android's
 * package-visibility filtering (API 30+) and, confirmed on device (`emulator-5556`,
 * targetSdk 35), throws `NameNotFoundException` for a perfectly ordinary system IME like Gboard
 * when this app declares no `<queries>` for it - which silently misclassified every system IME as
 * third-party and always showed the banner. The `ApplicationInfo` already attached to the
 * `InputMethodInfo` the system handed back has no such filtering: it is not a second, separately
 * visibility-checked lookup.
 */
private fun computeThirdPartyImeActive(context: Context): Boolean {
    val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?: return false
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val systemPackages = inputMethodManager?.enabledInputMethodList
        ?.filter { isSystemApplicationInfo(it.serviceInfo?.applicationInfo) }
        ?.mapTo(HashSet()) { it.packageName }
        ?: emptySet()
    return !isSystemIme(imeId, systemPackages)
}

/**
 * Whether [info] is a system app (`ApplicationInfo.FLAG_SYSTEM`) or a system app that has been
 * updated in place, e.g. via the Play Store (`ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`) - both are
 * still shipped/reviewed as part of the OS image rather than a third-party app the user separately
 * installed. A null [info] (should not normally happen for an enabled IME, but is defensive against
 * an unexpected shape) is treated as not-system, which is the safer default: it shows the banner
 * rather than silently trusting an unknown package.
 */
private fun isSystemApplicationInfo(info: ApplicationInfo?): Boolean {
    val flags = info?.flags ?: return false
    return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

/** Reads `debug.nomessages.allow_capture`; see [readSystemProperty] for the mechanism and failure mode. */
private fun debugCaptureProperty(): String = readSystemProperty("debug.nomessages.allow_capture", "0")

/**
 * Reads a system property via `android.os.SystemProperties.get(String, String)`.
 *
 * `SystemProperties` is not public API (no import is available), so it is reached by reflection.
 * `debug.*` properties are readable by any app without root, which is exactly the point: a
 * developer can flip one from `adb shell` without a rooted device, while SELinux still restricts
 * *writing* `debug.*` to the `shell`/`su` domains. Any reflection failure - class missing, method
 * signature changed, `SecurityException`, or anything else - falls back to [fallback], which every
 * caller must choose to be the *secure* value, so a reflection problem can only ever fail closed.
 *
 * Callers: [debugCaptureProperty] (keeps `FLAG_SECURE` applied on failure) and, in the debug-only
 * source set, `dev.mx3.nomessages.debug.DebugQrReceiver` (keeps the QR hook disabled on failure).
 */
internal fun readSystemProperty(name: String, fallback: String): String = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
        .invoke(null, name, fallback) as? String
        ?: fallback
} catch (_: Throwable) {
    // Broad on purpose: a missing class, a changed method signature, a SecurityException, or any
    // other reflection failure must never crash the app, and must never be treated as opt-in.
    fallback
}

private class LocalOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class LocalVaultExport : ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
