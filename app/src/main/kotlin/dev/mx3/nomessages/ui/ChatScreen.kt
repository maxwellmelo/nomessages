package dev.mx3.nomessages.ui

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import dev.mx3.nomessages.ui.icons.filled.AttachFile
import dev.mx3.nomessages.ui.icons.filled.CameraAlt
import dev.mx3.nomessages.ui.icons.filled.DeleteOutline
import dev.mx3.nomessages.ui.icons.filled.Description
import dev.mx3.nomessages.ui.icons.filled.Group
import dev.mx3.nomessages.ui.icons.filled.Mic
import dev.mx3.nomessages.ui.icons.filled.Pause
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Send
import dev.mx3.nomessages.ui.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mx3.nomessages.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(state: UiState, actions: UiActions, isGroup: Boolean, thirdPartyImeActive: Boolean = false) {
    var draft by remember(state.selectedChat) { mutableStateOf("") }
    var attachmentMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var leaveConfirmation by remember { mutableStateOf(false) }
    var removeConfirmation by remember { mutableStateOf<ContactUi?>(null) }
    // The message a long press picked for forwarding (null = picker closed), plus the recipients
    // ticked in the picker. Keying the selection on the message itself is what guarantees a fresh,
    // empty tick list every time the picker opens: closing and long-pressing another message can
    // never inherit the previous message's recipients.
    var forwardingMessage by remember { mutableStateOf<MessageUi?>(null) }
    var forwardSelection by remember(forwardingMessage) { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    var lastScrolledChat by remember { mutableStateOf<String?>(null) }
    // Read during composition, before this pass's layout adjusts the scroll offset for the item
    // that was just added, so this reflects whether the reader was at the bottom BEFORE the new
    // message - not after - which is what makes "don't yank the reader down" work below.
    val wasAtBottom = remember(state.messages.size) { isAtBottom(listState.firstVisibleItemIndex) }
    val composerEnabled = !isGroup || state.selectedGroupStatus == GroupStatus.NONE || state.selectedGroupStatus == GroupStatus.READY
    val title = state.selectedTitle.ifBlank {
        state.contacts.firstOrNull { it.id == state.selectedChat }?.alias.orEmpty()
    }

    fun send() {
        val message = draft.trim()
        if (composerEnabled && message.isNotEmpty()) {
            draft = ""
            actions.sendText(message)
        }
    }

    DisposableEffect(state.selectedChat) {
        onDispose {
            draft = ""
            attachmentMenu = false
            groupMenu = false
            // Leaving the conversation drops the pending forward too: the picked message belongs to
            // the chat being left, so it must not survive into the next one.
            forwardingMessage = null
        }
    }
    // The list is rendered newest-first with reverseLayout = true (see the LazyColumn below), so
    // the newest message is always index 0. Opening a chat (or switching to a different one) snaps
    // straight to it with no animation; a message arriving/sent afterwards only animates into view
    // when the reader was already at the bottom, so reading older history is never interrupted.
    LaunchedEffect(state.selectedChat, state.messages.size) {
        val chatChanged = lastScrolledChat != state.selectedChat
        lastScrolledChat = state.selectedChat
        if (state.messages.isEmpty()) return@LaunchedEffect
        val newestIndex = scrollIndexForNewestMessage(state.messages.size, reverseLayout = true)
        if (chatChanged) listState.scrollToItem(newestIndex)
        else if (shouldAutoScrollToNewMessage(wasAtBottom)) listState.animateScrollToItem(newestIndex)
    }
    LaunchedEffect(composerEnabled) {
        if (!composerEnabled) attachmentMenu = false
    }
    BackHandler(onBack = actions::closeChat)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(title, modifier = Modifier.size(38.dp), group = isGroup)
                            Spacer(Modifier.width(10.dp))
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = actions::closeChat) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary),
                    actions = {
                        if (isGroup && state.selectedGroupStatus != GroupStatus.LEFT) {
                            Box {
                                IconButton(onClick = { groupMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                                }
                                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                                    if (state.canManageSelectedGroup && state.selectedGroupMembers.isNotEmpty()) {
                                        state.selectedGroupMembers.forEach { member ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.remove_member) + ": " + member.alias) },
                                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                                onClick = {
                                                    groupMenu = false
                                                    removeConfirmation = member
                                                },
                                            )
                                        }
                                        HorizontalDivider()
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (state.selectedGroupStatus == GroupStatus.PENDING) R.string.cancel_pending_group
                                                    else R.string.leave_group,
                                                ),
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                                        onClick = {
                                            groupMenu = false
                                            leaveConfirmation = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                NetworkBanner(state.network, compact = true)
                if (isGroup) GroupStatusBanner(state.selectedGroupStatus)
            }
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (thirdPartyImeActive) {
                    Text(
                        stringResource(R.string.third_party_keyboard_warning),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.recordingAudio) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.recording), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = actions::stopAudio) { Text(stringResource(R.string.stop_recording)) }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box {
                        IconButton(onClick = { attachmentMenu = true }, enabled = composerEnabled && !state.recordingAudio) {
                            Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach))
                        }
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach)) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                onClick = {
                                    attachmentMenu = false
                                    actions.pickAttachment()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.take_photo)) },
                                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                onClick = {
                                    attachmentMenu = false
                                    actions.capturePhoto()
                                },
                            )
                        }
                    }
                    PrivateImeScope {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            enabled = composerEnabled && !state.recordingAudio,
                            placeholder = { Text(stringResource(R.string.message_hint)) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            keyboardOptions = privateKeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                    }
                    IconButton(
                        enabled = state.recordingAudio || composerEnabled,
                        onClick = when {
                            state.recordingAudio -> actions::stopAudio
                            draft.isNotBlank() -> ::send
                            else -> actions::startAudio
                        },
                    ) {
                        Icon(
                            imageVector = when {
                                state.recordingAudio -> Icons.Default.StopCircle
                                draft.isNotBlank() -> Icons.AutoMirrored.Filled.Send
                                else -> Icons.Default.Mic
                            },
                            contentDescription = stringResource(
                                when {
                                    state.recordingAudio -> R.string.stop_recording
                                    draft.isNotBlank() -> R.string.send
                                    else -> R.string.record_audio
                                },
                            ),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (state.messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_messages), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp),
                state = listState,
                // state.messages is already newest-first (it mirrors the messages_chat_time query,
                // ORDER BY ts DESC, id DESC), so reverseLayout anchors index 0 - the newest message
                // - at the bottom of the screen, next to the composer, exactly like WhatsApp. This
                // keeps the query, the controller, and every other consumer of state.messages
                // (there are none besides this screen) untouched.
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(state.messages.size, key = { state.messages[it].id }) { index ->
                    MessageBubble(state.messages[index], actions, onForward = { forwardingMessage = it })
                }
            }
        }
    }

    if (leaveConfirmation) {
        AlertDialog(
            onDismissRequest = { leaveConfirmation = false },
            title = {
                Text(
                    stringResource(
                        if (state.selectedGroupStatus == GroupStatus.PENDING) R.string.cancel_pending_group_confirm
                        else R.string.leave_group_confirm,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirmation = false
                    state.selectedChat?.let(actions::leaveGroup)
                }) {
                    Text(
                        stringResource(
                            if (state.selectedGroupStatus == GroupStatus.PENDING) R.string.cancel_pending_group
                            else R.string.leave_group,
                        ),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { leaveConfirmation = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    removeConfirmation?.let { member ->
        AlertDialog(
            onDismissRequest = { removeConfirmation = null },
            title = { Text(stringResource(R.string.remove_member_confirm, member.alias)) },
            confirmButton = {
                TextButton(onClick = {
                    removeConfirmation = null
                    state.selectedChat?.let { actions.removeMember(it, member.id) }
                }) { Text(stringResource(R.string.remove_member)) }
            },
            dismissButton = { TextButton(onClick = { removeConfirmation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Recipient picker. A long press on a bubble opens it directly - there is no intermediate
    // one-item context menu - because "Encaminhar" is the only action a bubble offers today, so a
    // menu step would be a second tap for no choice. The targets themselves come ready from
    // `state.forwardTargets` (paired contacts + READY groups); this screen only ticks them.
    forwardingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { forwardingMessage = null },
            title = { Text(stringResource(R.string.forward_to)) },
            text = {
                if (state.forwardTargets.isEmpty()) {
                    Text(stringResource(R.string.forward_no_targets))
                } else {
                    // LazyColumn, not Column: an AlertDialog's body is height-bounded and a vault
                    // can hold up to 1024 contacts, so the list has to scroll and recycle instead
                    // of composing every target at once.
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(state.forwardTargets, key = { it.id }) { target ->
                            val checked = target.id in forwardSelection
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { forwardSelection = toggleForwardTarget(forwardSelection, target.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The row owns the toggle; the checkbox is the state readout for it,
                                // so tapping either the row or the box does exactly the same thing.
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { forwardSelection = toggleForwardTarget(forwardSelection, target.id) },
                                )
                                Spacer(Modifier.width(4.dp))
                                if (target.isGroup) {
                                    Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canConfirmForward(forwardSelection),
                    onClick = {
                        // One call with the whole selection: the controller fans it out into a
                        // separate, unlinked envelope per destination.
                        actions.forwardMessage(message.id, forwardSelection.toList())
                        forwardingMessage = null
                    },
                ) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { forwardingMessage = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupStatusBanner(status: GroupStatus) {
    val message = when (status) {
        GroupStatus.NONE, GroupStatus.READY -> return
        GroupStatus.PENDING -> R.string.group_pending_banner
        GroupStatus.FAILED -> R.string.group_failed_banner
        GroupStatus.LEFT -> R.string.group_left_banner
    }
    Surface(
        color = if (status == GroupStatus.PENDING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (status == GroupStatus.PENDING) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            stringResource(message),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * One chat bubble. A long press anywhere on it calls [onForward] with [message], which is what opens
 * the recipient picker in [ChatScreen].
 *
 * The long press is a bare `detectTapGestures(onLongPress = ...)` - it never declares an `onTap`, so
 * it cannot consume a short tap and cannot interfere with the interactive children below it (the
 * audio bubble's tap-to-seek/drag-to-seek waveform, its play and speed buttons, the tap-to-open
 * media bubbles). `Modifier.combinedClickable` would be the idiomatic one-liner, but it is still an
 * experimental Foundation API and this module compiles with `allWarningsAsErrors`, so an opt-in that
 * later becomes unnecessary (or is missing) would break the build; a plain gesture detector has no
 * such coupling.
 *
 * Children that own a tap gesture get [onLongPress] handed down rather than relying on this detector
 * alone - see [attachmentTapGestures]. When both this detector and a child's fire for the same press
 * the result is identical (the same message is picked twice), so the overlap is harmless.
 */
@Composable
private fun MessageBubble(message: MessageUi, actions: UiActions, onForward: (MessageUi) -> Unit) {
    val onLongPress = { onForward(message) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) },
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(9.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                // Provenance marker for a message this vault re-sent. It says only "this was
                // forwarded" - deliberately no origin chat, no original sender, no original
                // timestamp - because the copy carries none of that and must not imply it does.
                if (message.forwarded) {
                    Text(
                        "↪ " + stringResource(R.string.forwarded_label),
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                message.attachmentId?.let { id ->
                    when (message.mimeType?.let(::attachmentKind)) {
                        AttachmentKind.AUDIO -> AudioBubble(attachmentId = id, actions = actions, onLongPress = onLongPress)
                        AttachmentKind.IMAGE -> ImageBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions, onLongPress = onLongPress)
                        AttachmentKind.VIDEO -> VideoBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions, onLongPress = onLongPress)
                        // PDF/unsupported fall back to the generic row below.
                        else -> GenericAttachmentRow(attachmentId = id, attachmentName = message.attachmentName, actions = actions, onLongPress = onLongPress)
                    }
                }
                // Received message bodies render with selection explicitly disabled. A plain
                // `Text` is already non-selectable unless some ancestor `SelectionContainer` opts
                // it in, so this is not fixing a live leak - it makes the guarantee structural:
                // should any future screen (a forward picker, a search result list, a shared
                // wrapper) put a `SelectionContainer` above this list, incoming text still cannot
                // be selected, copied, dragged out of the app, or handed to the system text
                // toolbar. Outgoing bubbles keep the plain `Text` (the user's own words), and the
                // composer's `OutlinedTextField` is untouched - what the user is typing keeps
                // normal copy/paste.
                if (message.text.isNotEmpty()) {
                    if (message.outgoing) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        DisableSelection {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.outgoing) {
                        Spacer(Modifier.width(4.dp))
                        Text(deliveryMark(message.status), color = if (message.status.equals("read", ignoreCase = true)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * Tap-to-[onOpen] plus long-press-to-[onLongPress] for an attachment surface, as one gesture
 * detector keyed by [key].
 *
 * Why not `Modifier.clickable { onOpen() }` alongside the bubble's long-press detector: `clickable`
 * has no long-press timeout, so it reports a plain click on release no matter how long the finger
 * stayed down. A long press on an attachment would then open the recipient picker *and*, on release,
 * push the full-screen viewer on top of it. A single `detectTapGestures` makes the two mutually
 * exclusive - once the long press fires, the release is no longer a tap - which is exactly the
 * behaviour `combinedClickable` implements internally, minus its experimental opt-in.
 *
 * The explicit [semantics] block restores what dropping `clickable` would otherwise cost: the click
 * action and button role that let TalkBack (and any other accessibility service) still activate the
 * attachment. The only thing genuinely given up is the ripple, which never showed through a
 * full-bleed thumbnail anyway.
 */
private fun Modifier.attachmentTapGestures(key: Any, onOpen: () -> Unit, onLongPress: () -> Unit): Modifier =
    this
        .pointerInput(key) { detectTapGestures(onLongPress = { onLongPress() }, onTap = { onOpen() }) }
        .semantics {
            role = Role.Button
            onClick { onOpen(); true }
        }

/** The plain file-icon-and-name row used for any attachment this chat doesn't render inline. */
@Composable
private fun GenericAttachmentRow(attachmentId: String, attachmentName: String?, actions: UiActions, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .attachmentTapGestures(attachmentId, onOpen = { actions.openAttachment(attachmentId) }, onLongPress = onLongPress)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Text(attachmentName ?: stringResource(R.string.attachment), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
}

private val IMAGE_BUBBLE_MAX_HEIGHT = 220.dp

/**
 * WhatsApp-style inline image bubble: a small bounded thumbnail (via UiActions.loadImagePreview /
 * MediaPreviewCache - never the full decrypted image) that opens the full-screen zoomable, swipeable
 * viewer (see AttachmentViewer.kt) on tap. Falls back to [GenericAttachmentRow] if the thumbnail
 * fails to decode, so a corrupt or unsupported image never renders as a blank/dead area.
 */
@Composable
private fun ImageBubble(attachmentId: String, attachmentName: String?, actions: UiActions, onLongPress: () -> Unit) {
    var preview by remember(attachmentId) { mutableStateOf<ImagePreviewUi?>(null) }
    var failed by remember(attachmentId) { mutableStateOf(false) }
    LaunchedEffect(attachmentId) {
        val loaded = actions.loadImagePreview(attachmentId)
        if (loaded != null) preview = loaded else failed = true
    }

    when {
        failed -> GenericAttachmentRow(attachmentId = attachmentId, attachmentName = attachmentName, actions = actions, onLongPress = onLongPress)
        preview == null -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IMAGE_BUBBLE_MAX_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF223028)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
        else -> Image(
            bitmap = requireNotNull(preview).bitmap.asImageBitmap(),
            contentDescription = attachmentName ?: stringResource(R.string.attachment),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = IMAGE_BUBBLE_MAX_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .attachmentTapGestures(attachmentId, onOpen = { actions.openAttachment(attachmentId) }, onLongPress = onLongPress),
            contentScale = ContentScale.FillWidth,
        )
    }
}

/**
 * WhatsApp-style inline video bubble: a bounded poster-frame thumbnail (via
 * UiActions.loadVideoPreview / MediaPreviewCache - never the full decrypted video) with a centered
 * play-button overlay and a duration pill in the bottom-right corner, matching the audio bubble's
 * `formatDurationMs` labels. Tapping it opens the full-screen player (see AttachmentViewer.kt),
 * which owns the actual `MediaPlayer` and the draggable seek bar. Falls back to
 * [GenericAttachmentRow] if the poster fails to decode, same as [ImageBubble].
 */
@Composable
private fun VideoBubble(attachmentId: String, attachmentName: String?, actions: UiActions, onLongPress: () -> Unit) {
    var preview by remember(attachmentId) { mutableStateOf<VideoPreviewUi?>(null) }
    var failed by remember(attachmentId) { mutableStateOf(false) }
    LaunchedEffect(attachmentId) {
        val loaded = actions.loadVideoPreview(attachmentId)
        if (loaded != null) preview = loaded else failed = true
    }

    when {
        failed -> GenericAttachmentRow(attachmentId = attachmentId, attachmentName = attachmentName, actions = actions, onLongPress = onLongPress)
        preview == null -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IMAGE_BUBBLE_MAX_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF223028)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        }
        else -> {
            val loaded = requireNotNull(preview)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = IMAGE_BUBBLE_MAX_HEIGHT)
                    .clip(RoundedCornerShape(6.dp))
                    .attachmentTapGestures(attachmentId, onOpen = { actions.openAttachment(attachmentId) }, onLongPress = onLongPress),
            ) {
                Image(
                    bitmap = loaded.poster.asImageBitmap(),
                    contentDescription = attachmentName ?: stringResource(R.string.attachment),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play), tint = Color.White)
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = formatDurationMs(loaded.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * WhatsApp-style inline audio bubble: play/pause, a draggable/seekable waveform, current/total
 * time, and a cycling playback-speed control. Only one bubble app-wide is ever playing at a time -
 * enforced by AudioPlaybackCoordinator. Decrypted bytes used for playback are never cached; only
 * the small derived waveform (via UiActions.loadAudioPreview / MediaPreviewCache) survives between
 * compositions.
 */
@Composable
private fun AudioBubble(attachmentId: String, actions: UiActions, onLongPress: () -> Unit) {
    var preview by remember(attachmentId) { mutableStateOf<AudioPreviewUi?>(null) }
    var previewFailed by remember(attachmentId) { mutableStateOf(false) }
    LaunchedEffect(attachmentId) {
        val loaded = actions.loadAudioPreview(attachmentId)
        if (loaded != null) preview = loaded else previewFailed = true
    }

    var player by remember(attachmentId) { mutableStateOf<MediaPlayer?>(null) }
    var dataSource by remember(attachmentId) { mutableStateOf<MemoryMediaDataSource?>(null) }
    var playing by remember(attachmentId) { mutableStateOf(false) }
    var preparing by remember(attachmentId) { mutableStateOf(false) }
    var failed by remember(attachmentId) { mutableStateOf(false) }
    var positionMs by remember(attachmentId) { mutableStateOf(0) }
    var playerDurationMs by remember(attachmentId) { mutableStateOf(0) }
    var speedIndex by remember(attachmentId) { mutableStateOf(0) }
    val speeds = remember { floatArrayOf(1f, 1.5f, 2f) }
    val coroutineScope = rememberCoroutineScope()
    // Identity handle for this bubble's entry in AudioPlaybackCoordinator's teardown registry. Not
    // the attachment id: the full-screen viewer can hold a second player for the same attachment,
    // and the two must not displace each other in the registry.
    val playerToken = remember(attachmentId) { Any() }

    fun releasePlayer() {
        AudioPlaybackCoordinator.unregisterPlayer(playerToken)
        runCatching { player?.stop() }
        // Each step is independently guarded: a throwing release() must not skip closing the data
        // source, which is what zeroes this clip's decrypted bytes.
        runCatching { player?.release() }
        runCatching { dataSource?.close() }
        player = null
        dataSource = null
        playing = false
    }

    DisposableEffect(attachmentId) {
        onDispose {
            AudioPlaybackCoordinator.release(attachmentId)
            releasePlayer()
        }
    }

    LaunchedEffect(attachmentId) {
        snapshotFlow { AudioPlaybackCoordinator.activeId }.collect { active ->
            if (active != attachmentId && playing) {
                runCatching { player?.pause() }
                playing = false
            }
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.currentPosition ?: positionMs
            delay(120)
        }
    }

    fun applySpeed(mediaPlayer: MediaPlayer) {
        runCatching { mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speeds[speedIndex]) }
    }

    fun togglePlay() {
        val existing = player
        if (existing != null) {
            if (playing) {
                runCatching { existing.pause() }
                playing = false
                AudioPlaybackCoordinator.release(attachmentId)
            } else {
                AudioPlaybackCoordinator.requestPlay(attachmentId) {
                    runCatching { existing.pause() }
                    playing = false
                }
                applySpeed(existing)
                runCatching { existing.start() }
                playing = true
            }
            return
        }
        if (preparing) return
        preparing = true
        coroutineScope.launch {
            val loaded = actions.loadAudioBytes(attachmentId)
            if (loaded == null) {
                failed = true
                preparing = false
                return@launch
            }
            val source = MemoryMediaDataSource(loaded.bytes)
            val mediaPlayer = MediaPlayer()
            try {
                withContext(Dispatchers.IO) {
                    mediaPlayer.setDataSource(source)
                    mediaPlayer.prepare()
                }
                mediaPlayer.setOnCompletionListener { completed ->
                    playing = false
                    positionMs = 0
                    runCatching { completed.seekTo(0) }
                    AudioPlaybackCoordinator.release(attachmentId)
                }
                dataSource = source
                player = mediaPlayer
                // From here on this bubble owns a live MediaPlayer holding decrypted audio, whether
                // it is playing or merely paused. Registering the full teardown (not just a pause)
                // is what lets NoMessagesController.lock() stop it instantly and wipe `source` even
                // when the app is backgrounded and this composable will never be disposed.
                AudioPlaybackCoordinator.registerPlayer(playerToken) { releasePlayer() }
                playerDurationMs = mediaPlayer.duration.coerceAtLeast(0)
                AudioPlaybackCoordinator.requestPlay(attachmentId) {
                    runCatching { mediaPlayer.pause() }
                    playing = false
                }
                applySpeed(mediaPlayer)
                mediaPlayer.start()
                playing = true
            } catch (_: Exception) {
                failed = true
                runCatching { mediaPlayer.release() }
                source.close()
            } finally {
                preparing = false
            }
        }
    }

    fun seekTo(fraction: Float) {
        val duration = player?.duration?.takeIf { it > 0 } ?: playerDurationMs.takeIf { it > 0 } ?: preview?.durationMs ?: return
        val target = (duration * fraction.coerceIn(0f, 1f)).toInt()
        positionMs = target
        player?.let { runCatching { it.seekTo(target) } }
    }

    val totalDurationMs = player?.duration?.takeIf { it > 0 } ?: playerDurationMs.takeIf { it > 0 } ?: preview?.durationMs ?: 0
    val progress = if (totalDurationMs > 0) (positionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f
    val waveform = preview?.waveform ?: FloatArray(WAVEFORM_BUCKET_COUNT)
    // Read outside the Canvas draw lambda: DrawScope's onDraw is not a @Composable context, so
    // MaterialTheme.colorScheme can't be dereferenced inside it directly (see docs/changes/ChatScreen.kt.md).
    val playedBarColor = MaterialTheme.colorScheme.secondary

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = ::togglePlay, enabled = !preparing) {
                if (preparing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .pointerInput(attachmentId) {
                        // onLongPress here (rather than leaving the bubble's detector to handle it
                        // alone) is what stops a long press on the waveform from ALSO seeking on
                        // release: with a long-press handler declared, the release is no longer
                        // reported as a tap. Dragging still seeks, since a drag is not a long press.
                        detectTapGestures(
                            onLongPress = { onLongPress() },
                            onTap = { offset -> seekTo(offset.x / size.width) },
                        )
                    }
                    .pointerInput(attachmentId) {
                        detectDragGestures { change, _ -> seekTo(change.position.x / size.width) }
                    },
            ) {
                val barCount = waveform.size.coerceAtLeast(1)
                val gap = size.width / barCount
                val strokeWidth = (gap * 0.6f).coerceAtLeast(1f)
                waveform.forEachIndexed { index, amplitude ->
                    val x = gap * index + gap / 2f
                    val barHeight = amplitude.coerceIn(0.06f, 1f) * size.height
                    val played = (index + 1f) / barCount <= progress
                    drawLine(
                        color = if (played) playedBarColor else Color(0xFF9AA7A2),
                        start = Offset(x, size.height / 2f - barHeight / 2f),
                        end = Offset(x, size.height / 2f + barHeight / 2f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
            TextButton(onClick = {
                speedIndex = (speedIndex + 1) % speeds.size
                player?.let(::applySpeed)
            }) {
                Text(formatSpeedLabel(speeds[speedIndex]), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = if (failed || previewFailed) stringResource(R.string.media_error)
                else "${formatDurationMs(positionMs)} / ${formatDurationMs(totalDurationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp),
        )
    }
}
