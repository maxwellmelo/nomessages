package dev.mx3.nomessages.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.mx3.nomessages.ui.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mx3.nomessages.R
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

private const val MAX_TEXT_PREVIEW = 256 * 1024
private const val MAX_IMAGE_PREVIEW = 32 * 1024 * 1024
private const val MAX_MEDIA_PREVIEW = 64 * 1024 * 1024
private const val MAX_IMAGE_DIMENSION = 4_096L
private const val MAX_IMAGE_PIXELS = 8_000_000L
private const val MIN_IMAGE_ZOOM = 1f
private const val MAX_IMAGE_ZOOM = 6f
private const val SWIPE_NAVIGATE_THRESHOLD_PX = 120f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentViewer(
    attachment: AttachmentUi,
    gallery: List<String> = emptyList(),
    onNavigate: (String) -> Unit = {},
    onClose: () -> Unit,
) {
    // A swipe-triggered navigation that hasn't landed yet (the sibling image is still being
    // decrypted by the controller): shows a brief spinner over the still-visible current image
    // instead of a blank frame, and clears itself once `attachment` catches up to the requested id.
    var pendingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attachment.id) {
        if (attachment.id == pendingId) pendingId = null
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dismiss))
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    when (attachmentKind(attachment.mimeType)) {
                        AttachmentKind.IMAGE -> ImagePreview(
                            bytes = attachment.bytes,
                            attachmentId = attachment.id,
                            gallery = gallery,
                            onNavigate = { targetId ->
                                // Never preload a neighbor: this just re-invokes the same
                                // decrypt-and-replace path openAttachment already uses, so only the
                                // currently-viewed image's plaintext is ever resident.
                                if (pendingId == null && targetId != attachment.id) {
                                    pendingId = targetId
                                    onNavigate(targetId)
                                }
                            },
                        )
                        AttachmentKind.TEXT -> TextPreview(attachment.bytes)
                        AttachmentKind.PDF -> PdfPreview(attachment.bytes)
                        AttachmentKind.AUDIO -> MediaPreview(attachment.bytes, attachmentId = attachment.id, video = false)
                        AttachmentKind.VIDEO -> MediaPreview(attachment.bytes, attachmentId = attachment.id, video = true)
                        AttachmentKind.UNSUPPORTED -> UnsupportedPreview(R.string.format_unsupported)
                    }
                    if (pendingId != null) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(bytes: ByteArray, attachmentId: String, gallery: List<String>, onNavigate: (String) -> Unit) {
    if (bytes.size > MAX_IMAGE_PREVIEW) {
        UnsupportedPreview(R.string.attachment_preview_too_large)
        return
    }
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    var finished by remember(bytes) { mutableStateOf(false) }
    val ownedBitmap = remember(bytes) { OwnedResource<Bitmap> { it.wipeAndRecycle() } }
    DisposableEffect(ownedBitmap) {
        onDispose(ownedBitmap::dispose)
    }
    LaunchedEffect(bytes, ownedBitmap) {
        try {
            val decoded = withContext(Dispatchers.Default) {
                decodeBoundedImage(bytes)?.also(ownedBitmap::install)
            }
            currentCoroutineContext().ensureActive()
            bitmap = decoded
            finished = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finished = true
        } catch (_: OutOfMemoryError) {
            finished = true
        }
    }

    // Pinch-zoom/pan state, reset whenever new bytes replace the previous ones (a fresh decrypt,
    // whether from opening a different attachment or swipe-navigating to a sibling image).
    var scale by remember(bytes) { mutableFloatStateOf(MIN_IMAGE_ZOOM) }
    var offsetX by remember(bytes) { mutableFloatStateOf(0f) }
    var offsetY by remember(bytes) { mutableFloatStateOf(0f) }

    if (!finished) {
        CircularProgressIndicator()
    } else if (bitmap == null) {
        UnsupportedPreview(R.string.image_decode_error)
    } else {
        val index = gallery.indexOf(attachmentId)
        val canSwipe = gallery.size > 1 && index >= 0
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
                .pointerInput(attachmentId, gallery) {
                    // One hand-rolled gesture loop instead of stacking the library's
                    // detectTransformGestures with a separate detectDragGestures: transform gestures
                    // treat a single-finger drag as pan too, so it would race a sibling swipe
                    // detector for the same pointer events. Doing it by hand lets pointer count
                    // decide instead: 2+ fingers always pinch-zoom/pan; 1 finger pans only once
                    // already zoomed in, and otherwise only accumulates towards a swipe-navigate
                    // decision made once every pointer lifts.
                    awaitEachGesture {
                        var horizontalDrag = 0f
                        do {
                            val event = awaitPointerEvent()
                            val changes = event.changes
                            if (changes.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                scale = (scale * zoomChange).coerceIn(MIN_IMAGE_ZOOM, MAX_IMAGE_ZOOM)
                                if (scale > MIN_IMAGE_ZOOM) {
                                    offsetX += panChange.x
                                    offsetY += panChange.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (changes.size == 1) {
                                val change = changes[0]
                                val drag = change.positionChange()
                                if (scale > MIN_IMAGE_ZOOM) {
                                    if (drag != Offset.Zero) {
                                        offsetX += drag.x
                                        offsetY += drag.y
                                        change.consume()
                                    }
                                } else if (canSwipe) {
                                    horizontalDrag += drag.x
                                }
                            }
                        } while (changes.any { it.pressed })
                        if (scale <= MIN_IMAGE_ZOOM) {
                            if (canSwipe && horizontalDrag <= -SWIPE_NAVIGATE_THRESHOLD_PX) {
                                gallery.getOrNull(index + 1)?.let(onNavigate)
                            } else if (canSwipe && horizontalDrag >= SWIPE_NAVIGATE_THRESHOLD_PX) {
                                gallery.getOrNull(index - 1)?.let(onNavigate)
                            }
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentScale = ContentScale.Fit,
        )
    }
}

private fun decodeBoundedImage(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val sourceWidth = info.size.width
        val sourceHeight = info.size.height
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = min(
            1.0,
            min(
                MAX_IMAGE_DIMENSION.toDouble() / maxOf(sourceWidth, sourceHeight),
                sqrt(MAX_IMAGE_PIXELS.toDouble() / (sourceWidth.toDouble() * sourceHeight)),
            ),
        )
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        if (targetWidth != sourceWidth || targetHeight != sourceHeight) {
            decoder.setTargetSize(targetWidth, targetHeight)
        }
    }
}

@Composable
private fun PdfPreview(bytes: ByteArray) {
    if (bytes.size > MAX_PDF_BYTES) {
        UnsupportedPreview(R.string.attachment_preview_too_large)
        return
    }
    var document by remember(bytes) { mutableStateOf<MemoryPdfDocument?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    var pageIndex by remember(bytes) { mutableStateOf(0) }
    val ownedDocument = remember(bytes) { OwnedResource<MemoryPdfDocument> { it.closeAsync() } }
    DisposableEffect(ownedDocument) {
        onDispose(ownedDocument::dispose)
    }

    LaunchedEffect(bytes, ownedDocument) {
        try {
            val opened = withContext(Dispatchers.IO) {
                MemoryPdfDocument.open(bytes).also(ownedDocument::install)
            }
            currentCoroutineContext().ensureActive()
            document = opened
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } catch (_: OutOfMemoryError) {
            failed = true
        }
    }

    val activeDocument = document
    when {
        failed -> UnsupportedPreview(R.string.pdf_preview_error)
        activeDocument == null -> CircularProgressIndicator()
        else -> Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PdfPage(activeDocument, pageIndex, Modifier.fillMaxWidth().weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = pageIndex > 0, onClick = { pageIndex -= 1 }) {
                    Text(stringResource(R.string.previous_page))
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.pdf_page_count, pageIndex + 1, activeDocument.pageCount))
                Spacer(Modifier.weight(1f))
                Button(enabled = pageIndex + 1 < activeDocument.pageCount, onClick = { pageIndex += 1 }) {
                    Text(stringResource(R.string.next_page))
                }
            }
        }
    }
}

@Composable
private fun PdfPage(document: MemoryPdfDocument, pageIndex: Int, modifier: Modifier = Modifier) {
    var bitmap by remember(document, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(document, pageIndex) { mutableStateOf(false) }
    val ownedBitmap = remember(document, pageIndex) { OwnedResource<Bitmap> { it.wipeAndRecycle() } }
    DisposableEffect(ownedBitmap) {
        onDispose(ownedBitmap::dispose)
    }
    LaunchedEffect(document, pageIndex, ownedBitmap) {
        try {
            val rendered = withContext(Dispatchers.Default) {
                document.renderPage(pageIndex).also(ownedBitmap::install)
            }
            currentCoroutineContext().ensureActive()
            bitmap = rendered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } catch (_: OutOfMemoryError) {
            failed = true
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            failed -> UnsupportedPreview(R.string.pdf_page_error)
            bitmap == null -> CircularProgressIndicator()
            else -> Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_page, pageIndex + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun Bitmap.wipeAndRecycle() {
    if (!isRecycled) {
        if (isMutable) eraseColor(Color.TRANSPARENT)
        recycle()
    }
}

@Composable
private fun TextPreview(bytes: ByteArray) {
    if (bytes.size > MAX_TEXT_PREVIEW) {
        UnsupportedPreview(R.string.attachment_too_large)
        return
    }
    val text = remember(bytes) { bytes.decodeToString(throwOnInvalidSequence = false) }
    Text(
        text = text,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun UnsupportedPreview(message: Int) {
    Text(
        text = stringResource(message),
        modifier = Modifier.padding(32.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Full-screen audio/video playback. Only one `MediaPlayer` app-wide is ever meant to be actively
 * playing - the same rule [AudioPlaybackCoordinator] already enforces across inline audio bubbles -
 * so this player registers itself there too, keyed by [attachmentId]: starting it (autoplay on
 * prepare for video, or the play button for either) pauses whatever inline audio bubble (or another
 * full-screen player) was previously playing, and an audio bubble started afterwards pauses this one
 * back through the same mechanism.
 */
@Composable
private fun MediaPreview(bytes: ByteArray, attachmentId: String, video: Boolean) {
    if (bytes.size > MAX_MEDIA_PREVIEW) {
        UnsupportedPreview(R.string.attachment_preview_too_large)
        return
    }
    val copiedBytes = remember(bytes) { bytes.copyOf() }
    val dataSource = remember(copiedBytes) { MemoryMediaDataSource(copiedBytes) }
    val player = remember(dataSource) { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    // Identity handle for this player's entry in AudioPlaybackCoordinator's teardown registry -
    // see AudioBubble in ChatScreen.kt for the same pattern and why it is not the attachment id.
    val playerToken = remember(player) { Any() }

    fun pausePlayback() {
        runCatching { player.pause() }
        playing = false
    }

    DisposableEffect(player) {
        // Registered for the whole lifetime of the player, not only while it is playing: a paused
        // full-screen player still holds `copiedBytes`, this attachment's decrypted plaintext.
        // NoMessagesController.lock() runs this through AudioPlaybackCoordinator.reset(), which is the
        // only thing that stops playback and wipes those bytes when the auto-lock fires while the
        // app is backgrounded and no recomposition (and therefore no onDispose) can happen.
        AudioPlaybackCoordinator.registerPlayer(playerToken) {
            runCatching { player.pause() }
            runCatching { player.stop() }
            playing = false
            dataSource.close()
        }
        player.setOnPreparedListener {
            prepared = true
            durationMs = it.duration.coerceAtLeast(0)
            if (video) {
                AudioPlaybackCoordinator.requestPlay(attachmentId, ::pausePlayback)
                it.start()
                playing = true
            }
        }
        player.setOnCompletionListener {
            playing = false
            positionMs = 0
            runCatching { it.seekTo(0) }
            AudioPlaybackCoordinator.release(attachmentId)
        }
        player.setOnErrorListener { _, _, _ ->
            failed = true
            true
        }
        runCatching {
            player.setDataSource(dataSource)
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose {
            AudioPlaybackCoordinator.unregisterPlayer(playerToken)
            AudioPlaybackCoordinator.release(attachmentId)
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stop() }
            player.reset()
            player.release()
            dataSource.close()
        }
    }

    // Another player (an inline audio bubble, or a different full-screen viewer instance) taking
    // over the coordinator pauses this one, mirroring AudioBubble's own coordinator subscription.
    LaunchedEffect(attachmentId) {
        snapshotFlow { AudioPlaybackCoordinator.activeId }.collect { active ->
            if (active != attachmentId && playing) {
                runCatching { player.pause() }
                playing = false
            }
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition
            delay(200)
        }
    }

    if (failed) {
        UnsupportedPreview(R.string.media_error)
        return
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (video) {
            VideoSurface(player, Modifier.fillMaxWidth().weight(1f))
        }
        Button(
            enabled = prepared,
            onClick = {
                if (player.isPlaying) {
                    pausePlayback()
                    AudioPlaybackCoordinator.release(attachmentId)
                } else {
                    AudioPlaybackCoordinator.requestPlay(attachmentId, ::pausePlayback)
                    runCatching { player.start() }
                    playing = true
                }
            },
        ) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
            Text(stringResource(if (playing) R.string.pause else R.string.play), modifier = Modifier.padding(start = 8.dp))
        }
        // Draggable/seekable progress, shared by video and audio: dragging updates the shown
        // position immediately and seeks the player to match, the same "seek as you drag" feel as
        // the inline audio bubble's waveform in ChatScreen.kt.
        if (prepared && durationMs > 0) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                valueRange = 0f..durationMs.toFloat(),
                onValueChange = { value ->
                    positionMs = value.toInt()
                    runCatching { player.seekTo(positionMs) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${formatDurationMs(positionMs)} / ${formatDurationMs(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun VideoSurface(player: MediaPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).also { surface ->
                surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        runCatching { player.setDisplay(holder) }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        runCatching { player.setDisplay(null) }
                    }
                })
            }
        },
    )
}

/**
 * Wraps an already-decrypted in-memory byte array as a [MediaDataSource] for `MediaPlayer` /
 * `MediaExtractor`, zeroing the bytes on close. Internal (not private) so the inline audio bubble
 * player in `ChatScreen.kt` and the receiver-side waveform decoder in `AudioWaveformDecoder.kt` can
 * reuse the exact same memory-only pattern instead of duplicating it.
 */
internal class MemoryMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    private var closed = false

    @Synchronized
    override fun getSize(): Long = if (closed) 0 else bytes.size.toLong()

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("closed")
        if (position < 0 || position >= bytes.size) return -1
        val count = min(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, destinationOffset = offset, startIndex = position.toInt(), endIndex = position.toInt() + count)
        return count
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            bytes.fill(0)
        }
    }
}
