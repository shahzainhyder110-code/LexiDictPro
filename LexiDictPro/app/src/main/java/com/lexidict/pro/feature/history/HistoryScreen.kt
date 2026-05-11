package com.lexidict.pro.feature.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lexidict.pro.core.data.local.dao.SearchHistoryDao
import com.lexidict.pro.core.data.local.entities.SearchHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao
) : ViewModel() {

    val history: StateFlow<List<SearchHistoryEntity>> = searchHistoryDao
        .getRecentHistory(limit = 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeEntry(word: String) {
        viewModelScope.launch { searchHistoryDao.deleteByWord(word) }
    }

    fun clearAll() {
        viewModelScope.launch { searchHistoryDao.clearAll() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onWordClick: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val history by viewModel.history.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    val grouped: Map<String, List<SearchHistoryEntity>> = remember(history) {
        history.groupBy { entity ->
            val cal   = Calendar.getInstance().apply { timeInMillis = entity.searchedAt }
            val today = Calendar.getInstance()
            val yest  = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            when {
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
                cal.get(Calendar.YEAR) == yest.get(Calendar.YEAR) &&
                        cal.get(Calendar.DAY_OF_YEAR) == yest.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                    .format(Date(entity.searchedAt))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Search History", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AnimatedVisibility(visible = history.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
                EmptyHistoryState()
            }
            AnimatedVisibility(visible = history.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    grouped.forEach { (dateLabel, entries) ->
                        stickyHeader(key = "header_$dateLabel") {
                            Surface(modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.background) {
                                Text(text = dateLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
                            }
                        }
                        items(items = entries, key = { "${it.word}_${it.searchedAt}" }) { entity ->
                            HistoryItem(
                                entity = entity,
                                onClick = { onWordClick(entity.word) },
                                onRemove = { viewModel.removeEntry(entity.word) }
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null,
                tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear History?") },
            text = { Text("Delete all ${history.size} entries? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAll(); showClearDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryItem(
    entity: SearchHistoryEntity,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val timeStr = remember(entity.searchedAt) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(entity.searchedAt))
    }
    ListItem(
        headlineContent = {
            Text(entity.word, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(timeStr, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Icon(Icons.Filled.History, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp))
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
    HorizontalDivider(thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No search history", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Your recent searches will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center)
    }
}
