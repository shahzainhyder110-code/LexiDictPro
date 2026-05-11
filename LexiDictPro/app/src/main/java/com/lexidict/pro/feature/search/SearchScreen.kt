package com.lexidict.pro.feature.search

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidict.pro.core.domain.model.SearchMode
import com.lexidict.pro.core.domain.model.WordEntry

// ═══════════════════════════════════════════════════════════════
//  SEARCH SCREEN — Root Composable
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToEntry: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            SearchTopBar(
                query = state.query,
                onQueryChange = { viewModel.onEvent(SearchEvent.QueryChanged(it)) },
                onClear = { viewModel.onEvent(SearchEvent.ClearQuery) },
                onSearch = {
                    if (state.query.isNotBlank()) {
                        viewModel.onEvent(SearchEvent.WordSelected(state.query))
                        focusManager.clearFocus()
                    }
                },
                focusRequester = focusRequester
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when {
                // Suggestions overlay
                state.showSuggestions && state.suggestions.isNotEmpty() -> {
                    SuggestionsOverlay(
                        suggestions = state.suggestions,
                        history = state.searchHistory.map { it.word }.take(5),
                        onSuggestionClick = { word ->
                            viewModel.onEvent(SearchEvent.WordSelected(word))
                            focusManager.clearFocus()
                        }
                    )
                }

                // Empty state / history
                state.query.isEmpty() -> {
                    HomeContent(
                        recentHistory = state.searchHistory.take(8),
                        onHistoryClick = { viewModel.onEvent(SearchEvent.WordSelected(it)) },
                        onClearHistory = { viewModel.onEvent(SearchEvent.ClearHistory) }
                    )
                }

                // Loading
                state.isLoading -> {
                    LoadingIndicator()
                }

                // Results
                state.results.isNotEmpty() -> {
                    SearchResultsList(
                        results = state.results,
                        selectedEntry = state.selectedEntry,
                        onEntryClick = { entry ->
                            viewModel.onEvent(SearchEvent.WordSelected(entry.word))
                        },
                        onFavoriteToggle = { entry ->
                            viewModel.onEvent(SearchEvent.ToggleFavorite(entry))
                        }
                    )
                }

                // No results
                state.query.isNotBlank() && !state.isLoading -> {
                    NoResultsContent(query = state.query)
                }
            }
        }
    }

    // Error snackbar
    state.error?.let { error ->
        LaunchedEffect(error) {
            // Show snackbar
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SEARCH TOP BAR
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // App branding
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📖",
                    fontSize = 20.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LexiDict Pro",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Search dictionaries...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SUGGESTIONS OVERLAY
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SuggestionsOverlay(
    suggestions: List<String>,
    history: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        if (history.isNotEmpty()) {
            item {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            items(history) { word ->
                SuggestionItem(
                    text = word,
                    icon = Icons.Outlined.History,
                    onClick = { onSuggestionClick(word) }
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
        }

        items(suggestions) { suggestion ->
            SuggestionItem(
                text = suggestion,
                icon = Icons.Outlined.Search,
                onClick = { onSuggestionClick(suggestion) }
            )
        }
    }
}

@Composable
private fun SuggestionItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ═══════════════════════════════════════════════════════════════
//  SEARCH RESULTS LIST
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SearchResultsList(
    results: List<WordEntry>,
    selectedEntry: WordEntry?,
    onEntryClick: (WordEntry) -> Unit,
    onFavoriteToggle: (WordEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(results, key = { it.word }) { entry ->
            WordEntryCard(
                entry = entry,
                isSelected = selectedEntry?.word == entry.word,
                onEntryClick = { onEntryClick(entry) },
                onFavoriteToggle = { onFavoriteToggle(entry) }
            )
        }
    }
}

@Composable
private fun WordEntryCard(
    entry: WordEntry,
    isSelected: Boolean,
    onEntryClick: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    val elevation by animateDpAsState(
        targetValue = if (isSelected) 8.dp else 2.dp,
        animationSpec = spring(), label = "elevation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEntryClick)
            .animateItem(),
        elevation = CardDefaults.cardElevation(elevation),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.word,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Source dictionary pill
                    entry.results.firstOrNull()?.let { result ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = result.dictionaryName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Favorite + audio buttons
                Row {
                    if (entry.results.any { !it.audioPath.isNullOrBlank() }) {
                        IconButton(onClick = { /* play audio */ }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Pronounce",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            if (entry.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (entry.isFavorite) "Unfavorite" else "Favorite",
                            tint = if (entry.isFavorite) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Definition preview
            entry.results.firstOrNull()?.let { result ->
                Spacer(Modifier.height(8.dp))

                // Pronunciation if available
                result.pronunciation?.let { ipa ->
                    Text(
                        text = "/$ipa/",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Definition text (HTML stripped for preview)
                val plainText = result.definition
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

                Text(
                    text = plainText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (isSelected) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Multiple dictionary sources
                if (entry.results.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "+${entry.results.size - 1} more sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  HOME CONTENT
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeContent(
    recentHistory: List<com.lexidict.pro.core.domain.model.SearchHistoryEntry>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Hero banner
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
                Column {
                    Text("Search across all\nyour dictionaries",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Tap the search bar to begin",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Recent searches
        if (recentHistory.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent Searches",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClearHistory) {
                        Text("Clear")
                    }
                }
            }

            items(recentHistory) { entry ->
                HistoryChip(
                    word = entry.word,
                    onClick = { onHistoryClick(entry.word) }
                )
            }
        }
    }
}

@Composable
private fun HistoryChip(word: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.History, contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(word, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  LOADING + NO RESULTS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoResultsContent(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.SearchOff, contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("No results for \"$query\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Try a different spelling or search mode",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}
