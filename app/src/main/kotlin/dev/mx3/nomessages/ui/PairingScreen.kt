package dev.mx3.nomessages.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.mx3.nomessages.ui.icons.filled.QrCode
import dev.mx3.nomessages.ui.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.Result
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.mx3.nomessages.BuildConfig
import dev.mx3.nomessages.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private enum class PairingMode { QR, SCAN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingScreen(state: UiState, actions: UiActions, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(PairingMode.QR) }
    var alias by remember { mutableStateOf("") }
    var aliasSubmitted by remember(state.pairing?.peerFingerprint, state.pairing?.sas) { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { alias = "" } }
    LaunchedEffect(state.error, state.pairing?.waitingForPeer) {
        if (state.error != null && state.pairing?.waitingForPeer != true) aliasSubmitted = false
    }

    // QR format 2 (T4.16): the 120 s reading window of an offer nobody scanned now refreshes itself
    // instead of leaving a dead QR on screen. Every deadline decision lives in the pure
    // PairingLifecycle state machine (covered by PairingLifecycleTest); this effect only ticks and
    // dispatches, so the screen holds no timing rule of its own.
    // Distinct from the user pressing "Cancelar": this flag is only ever set from the CANCEL branch
    // just below, which - because screenOpen is hardcoded true in this effect - only fires when a
    // staged exchange's own 300 s deadline (PairingLifecycle.PENDING_TTL_MILLIS) ran out on its own.
    // Without it, a timed-out exchange would just vanish back to "start pairing" with no explanation,
    // reading as if the app had silently failed instead of a deadline the user could not have met in
    // time (see the peer-unreachable banner it appears alongside for the other half of this story).
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(state.pairing?.expiresAt, state.pairing?.sas, state.pairing?.completed) {
        while (true) {
            when (PairingLifecycle.nextAction(state.pairing, screenOpen = true, nowMillis = System.currentTimeMillis())) {
                PairingQrAction.REGENERATE -> { actions.showPairing(); return@LaunchedEffect }
                PairingQrAction.CANCEL -> { timedOut = true; actions.cancelPairing(); return@LaunchedEffect }
                PairingQrAction.NONE -> delay(1_000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (mode == PairingMode.SCAN && state.pairing?.completed != true) {
            QrScanner(
                modifier = Modifier.fillMaxSize().padding(padding),
                onScanned = { code ->
                    mode = PairingMode.QR
                    actions.readPairing(code)
                },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.pairing_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                val pairing = state.pairing
                if (pairing == null) {
                    // Only shown right after the ticker above cancelled a staged exchange on its own
                    // (see the comment at its declaration) - a manual "Cancelar" tap navigates away
                    // from this screen entirely, so it never reaches this branch with timedOut set.
                    if (timedOut) {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                stringResource(R.string.pairing_timed_out),
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(stringResource(R.string.start_pairing_first), style = MaterialTheme.typography.bodyLarge)
                            Button(
                                onClick = { timedOut = false; actions.showPairing() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.show_my_qr))
                            }
                            OutlinedButton(onClick = { mode = PairingMode.SCAN }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.scan_qr))
                            }
                        }
                    }
                } else if (pairing.completed) {
                    Text(stringResource(R.string.pairing_complete_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.pairing_complete_detail), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    pairing.offer?.let { PairingQr(it) }
                    ExpiryLabel(pairing.expiresAt, regenerating = false)
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.done))
                    }
                } else {
                    pairing.offer?.let { offer ->
                        Text(stringResource(R.string.your_pairing_qr), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        PairingQr(offer)
                    }
                    // Before an exchange is staged the countdown is the QR rotation, not a
                    // deadline the user can miss - so it reads "New QR in ..." and is followed by the
                    // standing reassurance that the rotation costs the other person nothing. Both are
                    // keyed off `sas == null`, which this composable already uses to lay itself out.
                    ExpiryLabel(pairing.expiresAt, regenerating = pairing.sas == null)
                    if (pairing.sas == null) {
                        Text(
                            stringResource(R.string.qr_previous_still_valid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    BundleFetchStatus(pairing, actions)
                    OutlinedButton(onClick = { mode = PairingMode.SCAN }, enabled = pairing.sas == null || pairing.waitingForPeer,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.scan_qr))
                    }
                    pairing.sas?.let { sas ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(stringResource(R.string.sas_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(sas, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.sas_instruction), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                pairing.peerFingerprint?.let { Text(stringResource(R.string.peer_fingerprint, it), style = MaterialTheme.typography.bodySmall) }
                                if (!aliasSubmitted && !pairing.waitingForPeer) {
                                    PrivateImeScope {
                                        OutlinedTextField(
                                            value = alias,
                                            onValueChange = { alias = it },
                                            label = { Text(stringResource(R.string.local_alias)) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            keyboardOptions = privateKeyboardOptions(),
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val finalAlias = alias.trim()
                                            alias = ""
                                            aliasSubmitted = true
                                            actions.confirmPairing(finalAlias)
                                        },
                                        // Also gated on the peer's key bundle having arrived and
                                        // matched the signed hash: confirming before that would only
                                        // fail later, at `finish`, with both QRs already exchanged.
                                        enabled = alias.isNotBlank() && PairingLifecycle.canConfirmSas(pairing),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.confirm_codes_match)) }
                                } else if (pairing.waitingForPeer) {
                                    Text(stringResource(R.string.waiting_confirmation_qr), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                } else {
                                    Text(stringResource(R.string.confirming_pairing), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    if (pairing.waitingForPeer) {
                        Text(stringResource(R.string.waiting_peer), fontWeight = FontWeight.SemiBold)
                    }
                    Text(stringResource(R.string.pairing_not_finished), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun PairingQr(payload: String) {
    // Instrumentação debug-only (ver KDoc de QrScannerHooks): publica o payload do QR que está na
    // tela para que o relay entre emuladores possa lê-lo sem fotografar a tela. Este composable é
    // o único ponto que renderiza QR no app - oferta, resposta e confirmação passam todos por
    // aqui -, então um único registro cobre as três etapas do pareamento.
    //
    // O `onDispose` só limpa o campo se ele ainda apontar para ESTE provider: quando a tela troca
    // de um QR para outro, a ordem de descarte/composição do Compose (leaving antes de entering)
    // já evita o problema, mas a checagem de identidade torna a limpeza correta mesmo que essa
    // ordem mude, em vez de apagar silenciosamente o registro de um QR mais novo.
    DisposableEffect(payload) {
        val provider: () -> ByteArray? = { payload.toByteArray(Charsets.ISO_8859_1) }
        QrScannerHooks.shownPayload = provider
        onDispose { if (QrScannerHooks.shownPayload === provider) QrScannerHooks.shownPayload = null }
    }
    // Crossfade (not just a swap) keyed on the payload: when an offer regenerates (T4.16 auto-refresh)
    // the still-visible old QR fades into the spinner for the new one - and the new image then pops
    // in once ready - instead of an instant replacement that reads as "the old one broke" rather than
    // "a new code is on its way". The QR-generation state below is remembered per Crossfade branch
    // (keyed on its own `framePayload`, the value Crossfade is currently animating), not on the outer
    // `payload` parameter, precisely so the outgoing branch keeps rendering its own already-finished
    // bitmap while the incoming branch starts its own generation from scratch.
    Crossfade(targetState = payload, label = "pairing_qr") { framePayload ->
        var bitmap by remember(framePayload) { mutableStateOf<Bitmap?>(null) }
        var finished by remember(framePayload) { mutableStateOf(false) }
        val ownedBitmap = remember(framePayload) {
            OwnedResource<Bitmap> {
                it.eraseColor(android.graphics.Color.WHITE)
                it.recycle()
            }
        }
        DisposableEffect(ownedBitmap) { onDispose(ownedBitmap::dispose) }
        LaunchedEffect(framePayload, ownedBitmap) {
            try {
                val generated = withContext(Dispatchers.Default) {
                    makeQr(framePayload, 720).also(ownedBitmap::install)
                }
                currentCoroutineContext().ensureActive()
                bitmap = generated
                finished = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finished = true
            } catch (_: OutOfMemoryError) {
                finished = true
            }
        }
        if (!finished) {
            CircularProgressIndicator()
        } else if (bitmap == null) {
            Text(stringResource(R.string.qr_render_error), color = MaterialTheme.colorScheme.error)
        } else {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = stringResource(R.string.your_pairing_qr),
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(1f).background(Color.White).padding(10.dp),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

private fun makeQr(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    } catch (failure: Throwable) {
        bitmap.eraseColor(android.graphics.Color.WHITE)
        bitmap.recycle()
        throw failure
    } finally {
        pixels.fill(android.graphics.Color.WHITE)
    }
}

/**
 * Reports the Tor fetch of the peer's PQXDH key bundle (T4.16).
 *
 * Nothing is drawn before an exchange is staged: while only this device's own QR is on screen there
 * is no peer to fetch from yet.
 */
@Composable
private fun BundleFetchStatus(pairing: PairingUi, actions: UiActions) {
    if (pairing.sas == null || pairing.completed) return
    val message = when (pairing.bundleStatus) {
        PairingBundleStatus.NONE -> return
        PairingBundleStatus.WAITING_FOR_TOR -> R.string.pairing_bundle_waiting_tor
        PairingBundleStatus.FETCHING -> R.string.pairing_bundle_fetching
        PairingBundleStatus.READY -> R.string.pairing_bundle_ready
        PairingBundleStatus.FAILED -> R.string.pairing_bundle_failed
    }
    val failed = pairing.bundleStatus == PairingBundleStatus.FAILED
    // Failed reuses the same errorContainer/onErrorContainer surface as the rest of the app's error
    // banners (NetworkBanner, GroupStatusBanner) instead of just tinting text red on a neutral card,
    // so "the peer could not be reached" reads as the same kind of problem here as it does elsewhere.
    val containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val color = when (pairing.bundleStatus) {
        PairingBundleStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        PairingBundleStatus.READY -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val active = pairing.bundleStatus == PairingBundleStatus.WAITING_FOR_TOR ||
                pairing.bundleStatus == PairingBundleStatus.FETCHING
            if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(message), color = color, textAlign = TextAlign.Center)
            // The fetch itself can legitimately take up to ~40 s (a freshly published onion service
            // often needs a few attempts before it answers - see startBundleFetch); a plain spinner
            // with no time expectation reads as "stuck" well before that. Only shown once the fetch is
            // actually in flight (not during the separate WAITING_FOR_TOR reason for not moving yet).
            if (pairing.bundleStatus == PairingBundleStatus.FETCHING) {
                Text(
                    stringResource(R.string.pairing_bundle_fetching_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
            // The retry is offered only while the exchange deadline still allows one; after it,
            // another circuit would be spent on an exchange the engine already refuses.
            val retryable by produceState(initialValue = false, pairing.bundleStatus, pairing.expiresAt) {
                while (true) {
                    value = PairingLifecycle.canRetryBundleFetch(pairing, System.currentTimeMillis())
                    delay(1_000)
                }
            }
            if (retryable) {
                OutlinedButton(onClick = actions::retryPairingBundle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pairing_bundle_retry))
                }
            } else if (failed) {
                // Failed but no retry left to offer (deadline almost up): say so instead of leaving a
                // failure message on screen with no next step - the ticker in PairingScreen cancels
                // the exchange itself moments later and explains that part via `pairing_timed_out`.
                Text(
                    stringResource(R.string.pairing_bundle_failed_no_retry),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ExpiryLabel(expiresAt: Long, regenerating: Boolean) {
    val remaining by produceState(initialValue = remainingMillis(expiresAt), expiresAt) {
        // `initialValue` is used exactly once, when this label first enters composition; a later
        // key change relaunches this block but KEEPS the previous `value`. Without the assignment
        // below, the first expiry latched `value` at 0 and `while (value > 0)` then refused to run
        // even after the automatic refresh published a brand-new `expiresAt` - so the screen read
        // "Expires in expired" under a QR that was in fact freshly minted and perfectly scannable.
        // Measured on emulator-5556 on 2026-09-17: the label said "expired" while the QR on screen
        // decoded to an offer created 15 s earlier. Re-seeding from the current key makes the
        // producer idempotent with respect to how many times it has already run.
        value = remainingMillis(expiresAt)
        while (value > 0) {
            delay(1_000)
            value = remainingMillis(expiresAt)
        }
    }
    val label = PairingLifecycle.countdownLabel(remaining) ?: stringResource(R.string.expired)
    // Neutral for most of the countdown, an amber warning for the last few seconds
    // (PairingLifecycle.isExpiringSoon), and only error-red once it actually hits zero - a gentler
    // progression than jumping straight from neutral to red the instant the clock runs out, which
    // read as more alarming than the regeneration/cancel it precedes actually is.
    // [regenerating] keeps the existing progression but reframes it: while only this device's own
    // QR is up, hitting zero rotates the QR and costs the other person nothing (their copy stays
    // valid for its own full window), so it never turns error-red and the label says "New QR in"
    // rather than "Expires in". Once an exchange is staged it is a real deadline again and the
    // amber/red progression below applies unchanged.
    val color = when {
        remaining <= 0 && !regenerating -> MaterialTheme.colorScheme.error
        remaining <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        PairingLifecycle.isExpiringSoon(remaining) && !regenerating -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = if (regenerating) stringResource(R.string.qr_regenerates_in, label)
        else stringResource(R.string.qr_expires, label)
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}

// Delegates to the pure state machine so the countdown on screen and the refresh decision can never
// disagree about when a QR is expired, including about the seconds-vs-milliseconds promotion.
private fun remainingMillis(expiresAt: Long): Long =
    PairingLifecycle.remainingMillis(expiresAt, System.currentTimeMillis())

@Composable
private fun QrScanner(modifier: Modifier = Modifier, onScanned: (String) -> Unit) {
    val context = LocalContext.current

    // Instrumentação debug-only (ver KDoc de QrScannerHooks): entrega um QR injetado no MESMO
    // callback que a câmera real usa, ou seja, depois do decode e antes de actions.readPairing().
    // Tudo o que vem depois - parsing, verificação de assinatura, derivação do SAS - é exatamente
    // o caminho de produção.
    //
    // O registro fica aqui em cima, ANTES da checagem de permissão de câmera, de propósito: o
    // relay entre emuladores não depende da câmera, então exigir a permissão concedida para poder
    // injetar só adicionaria um passo de automação sem nenhum ganho. `rememberUpdatedState` mantém
    // o sink apontando para o `onScanned` mais recente sem precisar re-registrar a cada
    // recomposição; `DisposableEffect(Unit)` mantém um único registro por instância da tela, e a
    // checagem de identidade no `onDispose` impede que uma tela antiga apague o registro de uma
    // tela nova. Em release este sink existe mas nunca é invocado: nada em src/main lê o campo.
    val currentOnScanned by rememberUpdatedState(onScanned)
    DisposableEffect(Unit) {
        val sink: (ByteArray) -> Unit = { bytes -> currentOnScanned(String(bytes, Charsets.ISO_8859_1)) }
        QrScannerHooks.scanSink = sink
        onDispose { if (QrScannerHooks.scanSink === sink) QrScannerHooks.scanSink = null }
    }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        denied = !allowed
    }

    if (!granted) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.camera_permission_detail), textAlign = TextAlign.Center)
                if (denied) Text(stringResource(R.string.camera_denied), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
            }
        }
        return
    }

    Box(modifier.background(Color.Black)) {
        CameraPreview(
            onScanned = onScanned,
            onFailure = { cameraFailed = true },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            stringResource(R.string.point_camera),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(18.dp),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(1f).border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(18.dp)),
        )
        if (cameraFailed) {
            Text(
                stringResource(R.string.camera_unavailable),
                modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(18.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit, onFailure: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val providerFuture = remember(lifecycleOwner) { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
    val delivered = remember(lifecycleOwner) { AtomicBoolean(false) }
    val disposed = remember(lifecycleOwner) { AtomicBoolean(false) }

    androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var ownedProvider: ProcessCameraProvider? = null
        var ownedPreview: Preview? = null
        var ownedAnalysis: ImageAnalysis? = null
        providerFuture.addListener({
            if (disposed.get()) return@addListener
            runCatching {
                val provider = providerFuture.get()
                if (disposed.get()) {
                    return@runCatching
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                // A version-40-L QR (the real pairing payload, ~2.9 KB / 177 modules per side) needs
                // roughly 3-4 px per module to decode reliably, i.e. >= ~700px just for the QR region
                // alone - the previous default (no ResolutionSelector -> CameraX falls back to
                // 640x480) was too small even before accounting for the QR occupying only part of the
                // frame. Request 1920x1080 (falling back to the closest available resolution, higher
                // first) with a 16:9 preference; STRATEGY_KEEP_ONLY_LATEST (unchanged) still bounds
                // memory/backpressure at the higher resolution by dropping frames the analyzer can't
                // keep up with instead of queuing them.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image ->
                    if (BuildConfig.DEBUG) logAnalysisFrame(image)
                    val result = try {
                        image.decodeQr()
                    } finally {
                        image.close()
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(QR_SCAN_TAG, "decode attempt result=${if (result != null) "DECODED" else "none"}")
                    }
                    if (result != null && delivered.compareAndSet(false, true)) {
                        previewView.post { if (!disposed.get()) onScanned(result.text) }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                ownedProvider = provider
                ownedPreview = preview
                ownedAnalysis = analysis
            }.onFailure { if (!disposed.get()) onFailure() }
        }, mainExecutor)
        onDispose {
            disposed.set(true)
            val preview = ownedPreview
            val analysis = ownedAnalysis
            if (preview != null && analysis != null) runCatching { ownedProvider?.unbind(preview, analysis) }
            analyzerExecutor.shutdownNow()
        }
    }
}

private const val QR_SCAN_TAG = "NoMessagesQrScan"

/** Extracts the analysis frame's Y plane into a compact luminance buffer and delegates the actual
 *  decode (including rotation/polarity fallback) to the pure, unit-tested [decodeQrLuminance]. */
private fun ImageProxy.decodeQr(): Result? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val data = if (pixelStride == 1) {
        ByteArray(buffer.remaining()).also(buffer::get)
    } else {
        ByteArray(width * height).also { compact ->
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            try {
                for (row in 0 until height) for (column in 0 until width) {
                    val sourceIndex = row * rowStride + column * pixelStride
                    if (sourceIndex < bytes.size) compact[row * width + column] = bytes[sourceIndex]
                }
            } finally {
                bytes.fill(0)
            }
        }
    }
    val dataWidth = if (pixelStride == 1) rowStride else width
    return try {
        decodeQrLuminance(data, dataWidth, width, height)
    } finally {
        data.fill(0)
    }
}

/** Debug-only per-frame diagnostic log: dimensions/strides/rotation are exactly what
 *  [PlanarYUVLuminanceSource]/[decodeQrLuminance] consume, so any mismatch (e.g. a frame far
 *  smaller than expected, or an unexpected rowStride/pixelStride) is visible directly in logcat
 *  without needing a debugger. Cheap (one log line per frame) - safe to keep gated behind
 *  BuildConfig.DEBUG permanently. */
private fun logAnalysisFrame(image: ImageProxy) {
    val plane = image.planes.firstOrNull()
    Log.d(
        QR_SCAN_TAG,
        "frame width=${image.width} height=${image.height} " +
            "rowStride=${plane?.rowStride} pixelStride=${plane?.pixelStride} " +
            "rotationDegrees=${image.imageInfo.rotationDegrees} format=${image.format}",
    )
}
