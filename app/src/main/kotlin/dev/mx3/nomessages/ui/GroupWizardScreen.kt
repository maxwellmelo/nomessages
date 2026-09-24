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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mx3.nomessages.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupWizardScreen(state: UiState, actions: UiActions, onBack: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    val selected = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    var checkBaseline by remember { mutableLongStateOf(-1L) }
    var requestedMembers by remember { mutableStateOf<Set<String>?>(null) }
    val checkComplete = completedGroupCheck(
        baselineRevision = checkBaseline,
        requestedMembers = requestedMembers,
        resultRevision = state.groupCheckRevision,
        resultMembers = state.groupCheckMembers,
    )

    DisposableEffect(Unit) {
        onDispose {
            selected.clear()
            name = ""
            requestedMembers = null
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) requestedMembers = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_group)) },
                navigationIcon = {
                    IconButton(onClick = { if (step == 1) onBack() else step -= 1 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NetworkBanner(state.network, compact = true)
            WizardSteps(step)
            when (step) {
                1 -> MemberSelection(
                    contacts = state.contacts,
                    selected = selected,
                    onToggle = { id ->
                        if (id in selected) selected.remove(id) else if (selected.size < 99) selected.add(id)
                        requestedMembers = null
                    },
                    onContinue = { step = 2 },
                )
                2 -> GroupVerification(
                    selectedCount = selected.size,
                    missingPairs = state.missingPairs,
                    checkComplete = checkComplete,
                    checking = requestedMembers != null && !checkComplete,
                    busy = state.busy,
                    onCheck = {
                        checkBaseline = state.groupCheckRevision
                        requestedMembers = selected.toSet()
                        actions.checkGroup(selected.toList())
                    },
                    onContinue = { step = 3 },
                    onBack = { step = 1 },
                )
                else -> GroupName(
                    name = name,
                    onName = { name = it },
                    selectedCount = selected.size,
                    enabled = !state.busy,
                    onCreate = {
                        val finalName = name.trim()
                        name = ""
                        actions.createGroup(finalName, selected.toList())
                    },
                )
            }
        }
    }
}

@Composable
private fun WizardSteps(step: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(R.string.group_step_members, R.string.group_step_verify, R.string.group_step_name).forEachIndexed { index, label ->
            Surface(
                modifier = Modifier.weight(1f),
                color = if (step == index + 1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (step == index + 1) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(label), modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MemberSelection(
    contacts: List<ContactUi>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.select_group_members), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(contacts, key = { it.id }) { contact ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle(contact.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = contact.id in selected, onCheckedChange = { onToggle(contact.id) })
                    Spacer(Modifier.width(10.dp))
                    Avatar(contact.alias)
                    Spacer(Modifier.width(12.dp))
                    Text(contact.alias, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
            }
        }
        if (!validGroupSelection(selected.size)) {
            Text(stringResource(R.string.group_size_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Button(
            onClick = onContinue,
            enabled = validGroupSelection(selected.size),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.continue_label))
        }
    }
}

@Composable
private fun GroupVerification(
    selectedCount: Int,
    missingPairs: List<String>,
    checkComplete: Boolean,
    checking: Boolean,
    busy: Boolean,
    onCheck: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.group_graph_explanation), style = MaterialTheme.typography.bodyLarge)
        Text(pluralStringResource(R.plurals.selected_count, selectedCount, selectedCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCheck, enabled = !busy && !checking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.verify_group_graph))
        }
        if (checkComplete && !busy) {
            if (missingPairs.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.group_ready), modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(pluralStringResource(R.plurals.missing_pairs_title, missingPairs.size, missingPairs.size), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.missing_pairs_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(missingPairs) { pair -> Text("• $pair", modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
        } else if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.checking_group_graph))
            }
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.back)) }
            Button(onClick = onContinue, enabled = checkComplete && !busy && missingPairs.isEmpty(), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.continue_label))
            }
        }
    }
}

@Composable
private fun GroupName(
    name: String,
    onName: (String) -> Unit,
    selectedCount: Int,
    enabled: Boolean,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(pluralStringResource(R.plurals.selected_count, selectedCount, selectedCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrivateImeScope {
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = privateKeyboardOptions(),
            )
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onCreate, enabled = enabled && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create_group))
        }
    }
}
