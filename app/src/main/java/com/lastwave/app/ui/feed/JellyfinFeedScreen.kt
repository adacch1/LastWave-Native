package com.lastwave.app.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.jellyfin.JellyfinClient
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.shell.StreamingSourceToggle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home rows for Jellyfin mode. Kept apart from [FeedViewModel], whose init loads YouTube data. */
@HiltViewModel
class JellyfinFeedViewModel @Inject constructor(
    private val jellyfin: JellyfinClient,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val error: String? = null,
        val recent: List<SearchResultItem> = emptyList(),
        val albums: List<SearchResultItem> = emptyList(),
        val artists: List<SearchResultItem> = emptyList(),
    )

    val state = MutableStateFlow(State())
    private var job: Job? = null
    private var loadedAtMs = 0L

    init {
        viewModelScope.launch {
            jellyfin.connection.map { it.serverUrl to it.userId }.distinctUntilChanged()
                .collect { if (it.second.isNotBlank()) load() }
        }
    }

    // ponytail: fixed 30 min staleness, same idea as FeedViewModel.onVisible
    fun refreshIfStale() {
        // The init collector does the first load, so a zero loadedAtMs means it is still pending.
        if (loadedAtMs != 0L && job?.isActive != true && System.currentTimeMillis() - loadedAtMs > 30 * 60_000) load(refresh = true)
    }

    fun load(refresh: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            state.update { it.copy(loading = !refresh || it.loading, refreshing = refresh, error = null) }
            val all = listOf(
                async { jellyfin.albums("DateCreated", descending = true) },
                async { jellyfin.albums("Random") },
                async { jellyfin.artists() },
            ).awaitAll()
            loadedAtMs = System.currentTimeMillis()
            state.value = State(
                loading = false,
                recent = all[0].getOrDefault(emptyList()),
                albums = all[1].getOrDefault(emptyList()),
                artists = all[2].getOrDefault(emptyList()),
                error = all.firstNotNullOfOrNull { r ->
                    r.exceptionOrNull()?.let { it.message ?: "Couldn't reach your Jellyfin server" }
                },
            )
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JellyfinFeedScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: JellyfinFeedViewModel = hiltViewModel(),
    nav: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeFeed>().navigator,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val hasContent = state.recent.isNotEmpty() || state.albums.isNotEmpty() || state.artists.isNotEmpty()

    LifecycleStartEffect(Unit) {
        viewModel.refreshIfStale()
        onStopOrDispose { }
    }
    // Partial failure: the rows that loaded stay, the error shows as a snackbar.
    LaunchedEffect(state.error, hasContent) {
        val error = state.error ?: return@LaunchedEffect
        if (hasContent) {
            snackbarHostState.showSnackbar(error, withDismissAction = true)
            viewModel.dismissError()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 920.dp),
        ) {
            ExpressiveHeader(
                title = "Home",
                actions = {
                    HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                    HeaderActionIcon(Icons.Filled.Settings, "Settings", onOpenSettings)
                },
                content = { StreamingSourceToggle() },
            )

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.load(refresh = true) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.loading) {
                    FeedLoadingSkeleton()
                } else if (!hasContent) {
                    FeedEmptyState(
                        message = state.error ?: "Your Jellyfin library has no music yet.",
                        onRetry = { viewModel.load() },
                        onOpenSearch = onOpenSearch,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = FloatingNavDefaults.contentBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (state.recent.isNotEmpty()) {
                            item(key = "recent") {
                                Shelf("Recently added") { AlbumRow(state.recent, nav) }
                            }
                        }
                        if (state.albums.isNotEmpty()) {
                            item(key = "albums") {
                                Shelf("Albums") { AlbumRow(state.albums, nav) }
                            }
                        }
                        if (state.artists.isNotEmpty()) {
                            item(key = "artists") {
                                Shelf("Artists") { ArtistRow(state.artists, nav) }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .safeHorizontalContentPadding()
                .padding(bottom = FloatingNavDefaults.contentBottomPadding()),
        )
    }
}

@Composable
private fun Shelf(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            FeedSectionHeader(title = title)
            content()
        }
    }
}

@Composable
private fun AlbumRow(albums: List<SearchResultItem>, nav: ArtistAlbumNavigator) {
    FeedMediaRow {
        items(albums) { album ->
            FeedMediaCard(
                title = album.name,
                subtitle = album.artist.orEmpty(),
                artworkUrl = album.artworkUrl,
                fallbackIcon = Icons.Filled.Album,
                onClick = { nav.openAlbum(album.name, album.artist.orEmpty(), album.entityId) },
            )
        }
    }
}

@Composable
private fun ArtistRow(artists: List<SearchResultItem>, nav: ArtistAlbumNavigator) {
    FeedMediaRow {
        items(artists) { artist ->
            ArtistAvatarCard(
                artist = FeedArtist(artist.name, artist.entityId, artist.artworkUrl?.takeIf { "&tag=" in it }), // untagged = no art, show the letter fallback
                onClick = { nav.openArtist(artist.name, artist.entityId) },
            )
        }
    }
}
