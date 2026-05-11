package com.lexidict.pro.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lexidict.pro.core.data.repository.SearchRepository
import com.lexidict.pro.core.data.repository.FavoritesRepository
import com.lexidict.pro.core.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
//  SEARCH UI STATE
// ═══════════════════════════════════════════════════════════════

data class SearchUiState(
    val query: String = "",
    val searchMode: SearchMode = SearchMode.PREFIX,
    val isLoading: Boolean = false,
    val results: List<WordEntry> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val selectedEntry: WordEntry? = null,
    val error: String? = null,
    val searchHistory: List<SearchHistoryEntry> = emptyList(),
    val showSuggestions: Boolean = false
)

// ═══════════════════════════════════════════════════════════════
//  SEARCH EVENTS
// ═══════════════════════════════════════════════════════════════

sealed class SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent()
    data class WordSelected(val word: String) : SearchEvent()
    data class ModeChanged(val mode: SearchMode) : SearchEvent()
    object ClearQuery : SearchEvent()
    object ClearHistory : SearchEvent()
    data class ToggleFavorite(val entry: WordEntry) : SearchEvent()
}

// ═══════════════════════════════════════════════════════════════
//  SEARCH VIEW MODEL
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // Debounce job for real-time search
    private var searchJob: Job? = null
    private var suggestionJob: Job? = null

    init {
        // Load search history on startup
        viewModelScope.launch {
            searchRepository.getSearchHistory()
                .collect { history ->
                    _state.update { it.copy(searchHistory = history) }
                }
        }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged  -> onQueryChanged(event.query)
            is SearchEvent.WordSelected  -> onWordSelected(event.word)
            is SearchEvent.ModeChanged   -> onModeChanged(event.mode)
            SearchEvent.ClearQuery       -> clearQuery()
            SearchEvent.ClearHistory     -> clearHistory()
            is SearchEvent.ToggleFavorite -> toggleFavorite(event.entry)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  EVENT HANDLERS
    // ─────────────────────────────────────────────────────────────

    private fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query, showSuggestions = query.isNotEmpty()) }

        if (query.isBlank()) {
            _state.update { it.copy(
                results = emptyList(),
                suggestions = emptyList(),
                isLoading = false,
                showSuggestions = false
            )}
            return
        }

        // Debounced suggestion fetch (fast, 100ms)
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(100)
            val suggestions = searchRepository.getSuggestions(query)
            _state.update { it.copy(suggestions = suggestions) }
        }

        // Debounced full search (150ms)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            performSearch(query)
        }
    }

    private fun onWordSelected(word: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, showSuggestions = false, query = word) }

            // Save to history
            searchRepository.addToHistory(word)

            // Full exact lookup
            val results = searchRepository.searchExact(word)
            val isFav = favoritesRepository.isFavorite(word).first()

            _state.update { it.copy(
                isLoading = false,
                results = results,
                selectedEntry = results.firstOrNull()?.let { entry ->
                    entry.copy(isFavorite = isFav)
                }
            )}
        }
    }

    private fun onModeChanged(mode: SearchMode) {
        _state.update { it.copy(searchMode = mode) }
        val current = _state.value.query
        if (current.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                performSearch(current)
            }
        }
    }

    private fun clearQuery() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        _state.update { SearchUiState(searchHistory = it.searchHistory) }
    }

    private fun clearHistory() {
        viewModelScope.launch {
            searchRepository.clearHistory()
        }
    }

    private fun toggleFavorite(entry: WordEntry) {
        viewModelScope.launch {
            if (entry.isFavorite) {
                favoritesRepository.removeFromFavorites(entry.word)
            } else {
                val result = entry.results.firstOrNull() ?: return@launch
                favoritesRepository.addToFavorites(
                    FavoriteEntry(
                        word = entry.word,
                        definition = result.definition.take(1000),
                        dictionaryId = result.dictionaryId,
                        dictionaryName = result.dictionaryName
                    )
                )
            }
            _state.update { it.copy(
                selectedEntry = it.selectedEntry?.copy(isFavorite = !entry.isFavorite)
            )}
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  CORE SEARCH
    // ─────────────────────────────────────────────────────────────

    private suspend fun performSearch(query: String) {
        _state.update { it.copy(isLoading = true) }

        try {
            val searchQuery = SearchQuery(
                text = query,
                mode = _state.value.searchMode,
                maxResults = 50
            )

            val results = searchRepository.search(searchQuery)

            _state.update { it.copy(
                isLoading = false,
                results = results,
                error = null
            )}
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(
                isLoading = false,
                error = "Search failed: ${e.message}"
            )}
        }
    }
}
