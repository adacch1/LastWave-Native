package com.lastwave.app.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinClient
import com.lastwave.app.data.search.SearchHistoryRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchStatus { IDLE, LOADING, EMPTY, RESULTS }

@Immutable
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.TRACKS,
    val status: SearchStatus = SearchStatus.IDLE,
    val results: List<SearchResultItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val isShowingSuggestions: Boolean = false,
    /** Null until the source mode has loaded; true when Search reads Jellyfin instead of YouTube. */
    val jellyfin: Boolean? = null,
    val error: String? = null,
)

/**
 * YouTube Music & Last.fm search (or Jellyfin, in Jellyfin mode) with live auto-complete suggestions,
 * persistent search history, debounced search, and multi-tab results.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val historyRepository: SearchHistoryRepository,
    private val musicPlayer: MusicPlayer,
    private val jellyfinClient: JellyfinClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var debounceJob: Job? = null
    private var suggestionsJob: Job? = null
    private var searchQueueJob: Job? = null
    private var lastIssuedQuery: String = ""

    init {
        viewModelScope.launch {
            historyRepository.history.collect { history ->
                _uiState.update { it.copy(recentSearches = history) }
            }
        }
        // Already deduplicated, so a real change clears stale rows; the query isn't re-run.
        viewModelScope.launch {
            jellyfinClient.mode.collect { jf ->
                _uiState.update { s ->
                    if (s.jellyfin == null) s.copy(jellyfin = jf)
                    else s.copy(
                        jellyfin = jf,
                        tab = if (s.tab in JELLYFIN_TABS) s.tab else SearchTab.TRACKS,
                        status = SearchStatus.IDLE,
                        results = emptyList(),
                        suggestions = emptyList(),
                        error = null,
                        isShowingSuggestions = false,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query, isShowingSuggestions = query.isNotBlank() && it.jellyfin == false) }
        debounceJob?.cancel()
        suggestionsJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    status = SearchStatus.IDLE,
                    results = emptyList(),
                    suggestions = emptyList(),
                    isShowingSuggestions = false,
                )
            }
            return
        }

        // Fast suggestions debounce (120ms). Skipped outside YouTube mode so typed text never reaches Google.
        if (_uiState.value.jellyfin == false) {
            suggestionsJob = viewModelScope.launch {
                delay(120)
                val suggestions = repository.getSuggestions(query)
                if (_uiState.value.query == query) {
                    _uiState.update { it.copy(suggestions = suggestions) }
                }
            }
        }

        // Full search results debounce (400ms)
        debounceJob = viewModelScope.launch {
            delay(400)
            runSearch(query, saveToHistory = false)
        }
    }

    fun setTab(tab: SearchTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update {
            it.copy(
                tab = tab,
                isShowingSuggestions = false,
                results = emptyList(),
                status = if (it.query.isBlank()) SearchStatus.IDLE else SearchStatus.LOADING,
            )
        }
        val q = _uiState.value.query
        if (q.isNotBlank()) {
            debounceJob?.cancel()
            suggestionsJob?.cancel()
            viewModelScope.launch { runSearch(q, saveToHistory = false) }
        }
    }

    fun searchNow() {
        val q = _uiState.value.query
        if (q.isBlank()) return
        executeSearch(q)
    }

    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        historyRepository.add(trimmed)
        _uiState.update {
            it.copy(
                query = trimmed,
                isShowingSuggestions = false,
            )
        }
        viewModelScope.launch { runSearch(trimmed, saveToHistory = true) }
    }

    fun removeRecentSearch(query: String) {
        historyRepository.remove(query)
    }

    fun clearRecentSearches() {
        historyRepository.clear()
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(isShowingSuggestions = false) }
    }

    fun playResult(item: SearchResultItem) {
        searchQueueJob?.cancel()
        item.track?.let { t ->
            val q = _uiState.value.results.mapNotNull { it.track }
            // Not "Search": MusicPlayer special-cases that label.
            musicPlayer.playQueue(q, q.indexOf(t).coerceAtLeast(0), sourceLabel = "Jellyfin")
            return
        }
        val tab = _uiState.value.tab
        when (tab) {
            SearchTab.TRACKS -> {
                val selected = PlayableTrack(
                    title = item.name,
                    artist = item.artist.orEmpty(),
                    album = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    videoId = item.videoId,
                )
                // Start immediately. Similar song radio queue loads and extends infinitely.
                musicPlayer.play(selected, sourceLabel = "Search", startRadio = true)
            }
            SearchTab.ARTISTS, SearchTab.ALBUMS -> viewModelScope.launch {
                val tracks = runCatching { repository.songsFor(item) }.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) {
                    musicPlayer.playQueue(tracks.map { track ->
                        PlayableTrack(
                            title = track.title,
                            artist = track.artist.takeUnless { it == "Unknown artist" } ?: item.artist ?: item.name,
                            album = track.album ?: if (tab == SearchTab.ALBUMS) item.name else null,
                            artworkUrl = track.artworkUrl ?: item.artworkUrl,
                            videoId = track.videoId,
                            durationMs = track.durationSeconds?.takeIf { it > 0 }?.times(1_000L),
                        )
                    }, sourceLabel = "Search")
                }
            }
            SearchTab.PLAYLISTS, SearchTab.USERS -> Unit
        }
    }

    private suspend fun runSearch(query: String, saveToHistory: Boolean) {
        val tab = _uiState.value.tab
        val jf = _uiState.value.jellyfin ?: jellyfinClient.mode.first()
        lastIssuedQuery = query
        _uiState.update { it.copy(status = SearchStatus.LOADING, error = null) }
        if (saveToHistory) {
            historyRepository.add(query)
        }
        try {
            val results = if (jf) jellyfinClient.search(tab, query).getOrThrow() else repository.search(tab, query)
            // Stale-response guard: discard if the user has typed something
            // new (or the source has changed) since this call was issued.
            if (lastIssuedQuery != query || _uiState.value.query != query || _uiState.value.tab != tab ||
                (_uiState.value.jellyfin ?: jf) != jf) return
            _uiState.update {
                it.copy(
                    status = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.RESULTS,
                    results = results,
                )
            }
        } catch (e: Exception) {
            if (lastIssuedQuery != query || _uiState.value.query != query || _uiState.value.tab != tab ||
                (_uiState.value.jellyfin ?: jf) != jf) return
            _uiState.update {
                it.copy(status = SearchStatus.EMPTY, results = emptyList(), error = if (jf) e.message else null)
            }
        }
    }

    fun clearQuery() {
        debounceJob?.cancel()
        suggestionsJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                status = SearchStatus.IDLE,
                results = emptyList(),
                suggestions = emptyList(),
                isShowingSuggestions = false,
            )
        }
    }
}

// Tabs the Jellyfin source can serve.
private val JELLYFIN_TABS = setOf(SearchTab.TRACKS, SearchTab.ARTISTS, SearchTab.ALBUMS)
