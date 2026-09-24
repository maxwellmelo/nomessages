package dev.mx3.nomessages.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.mx3.nomessages.ui.icons.filled.GroupAdd
import dev.mx3.nomessages.ui.icons.automirrored.filled.Message
import dev.mx3.nomessages.ui.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mx3.nomessages.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsScreen(
    contacts: List<ContactUi>,
    onBack: () -> Unit,
    onPairing: () -> Unit,
    onNewGroup: () -> Unit,
    onContact: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewGroup, enabled = contacts.size >= 2) {
                        Icon(Icons.Default.GroupAdd, contentDescription = stringResource(R.string.new_group))
                    }
                    IconButton(onClick = onPairing) {
                        Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.pair_contact))
                    }
                },
            )
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            EmptyState(R.string.no_contacts, R.string.no_contacts_detail, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(contacts, key = { it.id }) { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onContact(contact.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(contact.alias)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(contact.fingerprint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactInfoScreen(
    contact: ContactUi,
    actions: UiActions,
    onBack: () -> Unit,
    onVerify: () -> Unit,
) {
    var alias by remember(contact.id) { mutableStateOf(contact.alias) }
    DisposableEffect(contact.id) { onDispose { alias = "" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contact_info)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(contact.alias, Modifier.padding(top = 8.dp))
            PrivateImeScope {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.alias)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = privateKeyboardOptions(),
                )
            }
            Button(
                onClick = { actions.renameContact(contact.id, alias.trim()) },
                enabled = alias.isNotBlank() && alias.trim() != contact.alias,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rename))
            }
            OutlinedButton(onClick = { actions.openChat(contact.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_chat))
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.fingerprint), style = MaterialTheme.typography.labelLarge)
                Text(contact.fingerprint, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.paired_at, contact.pairedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verify_again))
            }
        }
    }
}
