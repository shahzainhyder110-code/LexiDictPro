package com.lexidict.pro.feature.dictionaries

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lexidict.pro.core.data.repository.DictionaryRepository
import com.lexidict.pro.core.domain.model.Dictionary
import com.lexidict.pro.core.domain.model.DictionaryFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
//  DICTIONARIES VIEW MODEL
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class DictionariesViewModel @Inject constructor(
    private val dictionaryRepository: DictionaryRepository
) : ViewModel() {

    val dictionaries: StateFlow<List<Dictionary>> = dictionaryRepository
        .getAllDictionaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleEnabled(dictionary: Dictionary) = viewModelScope.launch {
        dictionaryRepository.setEnabled(dictionary.id, !dictionary.isEnabled)
    }

    fun deleteDictionary(dictionary: Dictionary) = viewModelScope.launch {
        dictionaryRepository.deleteDictionary(dictionary.id)
    }

    fun updatePriority(id: Long, priority: Int) = viewModelScope.launch {
        dictionaryRepository.updatePriority(id, priority)
    }
}

// ═══════════════════════════════════════════════════════════════
//  DICTIONARIES SCREEN
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionariesScreen(
    viewModel: DictionariesViewModel = hiltViewModel(),
    onNavigateToImport: () -> Unit
) {
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dictionaries", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToImport,
                icon = { Icon(Icons.Default.Add, "Add Dictionary") },
                text = { Text("Import") }
            )
        }
    ) { paddingValues ->
        if (dictionaries.isEmpty()) {
            EmptyDictionariesState(
                modifier = Modifier.padding(paddingValues),
                onImport = onNavigateToImport
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "${dictionaries.size} dictionaries installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(dictionaries, key = { it.id }) { dict ->
                    DictionaryCard(
                        dictionary = dict,
                        onToggleEnabled = { viewModel.toggleEnabled(dict) },
                        onDelete = { viewModel.deleteDictionary(dict) }
                    )
                }

                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  DICTIONARY CARD
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DictionaryCard(
    dictionary: Dictionary,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dictionary.isEnabled)
                MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Format icon + name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Format badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = formatColor(dictionary.format)
                    ) {
                        Text(
                            text = dictionary.format.displayName.take(3).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = dictionary.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        if (dictionary.author.isNotBlank()) {
                            Text(
                                text = dictionary.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Enable toggle
                Switch(
                    checked = dictionary.isEnabled,
                    onCheckedChange = { onToggleEnabled() }
                )
            }

            // Stats row
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatChip(icon = Icons.Outlined.Book, label = formatWordCount(dictionary.wordCount))
                if (dictionary.sourceLanguage.isNotBlank()) {
                    StatChip(
                        icon = Icons.Outlined.Language,
                        label = "${dictionary.sourceLanguage} → ${dictionary.targetLanguage}"
                    )
                }
                StatChip(icon = Icons.Outlined.Storage, label = formatFileSize(dictionary.sizeBytes))
            }

            // Index status
            if (!dictionary.isIndexed) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Indexing...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Delete button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove")
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove dictionary?") },
            text = { Text("\"${dictionary.name}\" will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(); showDeleteDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyDictionariesState(modifier: Modifier = Modifier, onImport: () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.MenuBook, null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))
            Text("No dictionaries yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("Import BGL, DSL, or StarDict files\nto get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onImport) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Import Dictionary")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun formatColor(format: DictionaryFormat): androidx.compose.ui.graphics.Color =
    when (format) {
        DictionaryFormat.BGL      -> MaterialTheme.colorScheme.primary
        DictionaryFormat.DSL      -> MaterialTheme.colorScheme.secondary
        DictionaryFormat.STARDICT -> MaterialTheme.colorScheme.tertiary
        DictionaryFormat.DICTD    -> MaterialTheme.colorScheme.error
        DictionaryFormat.ZIP      -> MaterialTheme.colorScheme.outline
        else                      -> MaterialTheme.colorScheme.surfaceVariant
    }

private fun formatWordCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M words"
    count >= 1_000     -> "${count / 1_000}K words"
    count > 0          -> "$count words"
    else               -> "Counting..."
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576     -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024         -> "${bytes / 1_024} KB"
    else                   -> "$bytes B"
}
