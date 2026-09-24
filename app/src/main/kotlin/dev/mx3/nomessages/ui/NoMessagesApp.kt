package dev.mx3.nomessages.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class Destination { CHATS, CONTACTS, CONTACT_INFO, PAIRING, GROUP, SETTINGS }

@Composable
fun NoMessagesApp(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    NoMessagesTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var dismissedNotice by remember(state.notice) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                key(state.configured, state.unlocked) {
                    when {
                        !state.configured -> SetupScreen(state, actions, thirdPartyImeActive)
                        !state.unlocked -> LockScreen(state, actions, thirdPartyImeActive)
                        else -> UnlockedApp(state, actions, thirdPartyImeActive)
                    }
                }
                if (!state.unlocked) {
                    StateMessageDialogs(
                        error = state.error,
                        notice = state.notice?.takeUnless { dismissedNotice },
                        onDismissError = actions::clearError,
                        onDismissNotice = { dismissedNotice = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockedApp(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean) {
    var destination by remember { mutableStateOf(Destination.CHATS) }
    var selectedContact by remember { mutableStateOf<String?>(null) }
    var dismissedNotice by remember(state.notice) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            selectedContact = null
            destination = Destination.CHATS
            dismissedNotice = true
        }
    }
    LaunchedEffect(state.selectedChat) {
        if (state.selectedChat != null && destination == Destination.GROUP) {
            destination = Destination.CHATS
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.selectedChat != null) {
            val selected = state.chats.firstOrNull { it.id == state.selectedChat }
            ChatScreen(
                state = state,
                actions = actions,
                isGroup = selected?.group == true,
                thirdPartyImeActive = thirdPartyImeActive,
            )
        } else {
            when (destination) {
                Destination.CHATS -> HomeScreen(
                    state = state,
                    actions = actions,
                    onContacts = { destination = Destination.CONTACTS },
                    onPairing = { destination = Destination.PAIRING },
                    onSettings = { destination = Destination.SETTINGS },
                )
                Destination.CONTACTS -> ContactsScreen(
                    contacts = state.contacts,
                    onBack = { destination = Destination.CHATS },
                    onPairing = { destination = Destination.PAIRING },
                    onNewGroup = { destination = Destination.GROUP },
                    onContact = {
                        selectedContact = it
                        destination = Destination.CONTACT_INFO
                    },
                )
                Destination.CONTACT_INFO -> {
                    val contact = state.contacts.firstOrNull { it.id == selectedContact }
                    if (contact == null) {
                        LaunchedEffect(Unit) { destination = Destination.CONTACTS }
                    } else {
                        ContactInfoScreen(
                            contact = contact,
                            actions = actions,
                            onBack = { destination = Destination.CONTACTS },
                            onVerify = {
                                actions.showPairing()
                                destination = Destination.PAIRING
                            },
                        )
                    }
                }
                Destination.PAIRING -> PairingScreen(
                    state = state,
                    actions = actions,
                    onBack = {
                        if (state.pairing != null) actions.cancelPairing()
                        destination = Destination.CONTACTS
                    },
                )
                Destination.GROUP -> GroupWizardScreen(
                    state = state,
                    actions = actions,
                    onBack = { destination = Destination.CONTACTS },
                )
                Destination.SETTINGS -> SettingsScreen(
                    state = state,
                    actions = actions,
                    onBack = { destination = Destination.CHATS },
                )
            }
        }

        state.attachment?.let { attachment ->
            AttachmentViewer(
                attachment = attachment,
                gallery = state.imageGallery,
                onNavigate = actions::openAttachment,
                onClose = actions::closeAttachment,
            )
        }
        if (state.busy) BusyOverlay()
    }

    BackHandler(enabled = state.selectedChat == null && destination != Destination.CHATS) {
        if (destination == Destination.PAIRING && state.pairing != null) actions.cancelPairing()
        destination = when (destination) {
            Destination.CONTACT_INFO, Destination.PAIRING, Destination.GROUP -> Destination.CONTACTS
            else -> Destination.CHATS
        }
    }

    StateMessageDialogs(
        error = state.error,
        notice = state.notice?.takeUnless { dismissedNotice },
        onDismissError = actions::clearError,
        onDismissNotice = { dismissedNotice = true },
    )
}
