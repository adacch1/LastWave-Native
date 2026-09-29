package com.lastwave.app.ui.search

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.jellyfin.JellyfinClient
import com.lastwave.app.data.jellyfin.JellyfinException
import com.lastwave.app.data.search.SearchHistoryRepository
import com.lastwave.app.data.search.SearchRepository
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.MusicPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: the mocked MusicPlayer and SearchHistoryRepository pull in Android classes.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val mode = MutableStateFlow(false)
    private val repository = mockk<SearchRepository>()
    private val jellyfin = mockk<JellyfinClient>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { jellyfin.mode } returns mode
        coEvery { repository.getSuggestions(any()) } returns listOf("suggestion")
        coEvery { repository.search(any(), any()) } returns listOf(SearchResultItem(name = "Song"))
        coEvery { jellyfin.search(any(), any()) } throws JellyfinException("Jellyfin server returned HTTP 500")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(): SearchViewModel {
        val history = mockk<SearchHistoryRepository>(relaxed = true)
        every { history.history } returns MutableStateFlow(emptyList())
        return SearchViewModel(repository, history, mockk<MusicPlayer>(relaxed = true), jellyfin)
    }

    @Test
    fun jellyfinMode_searchesJellyfinAndNeverAsksForSuggestions() = runTest(dispatcher) {
        mode.value = true
        val vm = viewModel()

        vm.setQuery("abc")
        assertThat(vm.uiState.value.isShowingSuggestions).isFalse()
        advanceTimeBy(600)

        coVerify(exactly = 0) { repository.getSuggestions(any()) }
        coVerify(exactly = 0) { repository.search(any(), any()) }
        coVerify(exactly = 1) { jellyfin.search(SearchTab.TRACKS, "abc") }
        assertThat(vm.uiState.value.status).isEqualTo(SearchStatus.EMPTY)
        assertThat(vm.uiState.value.error).isEqualTo("Jellyfin server returned HTTP 500")
    }

    @Test
    fun youTubeMode_keepsSuggestionsAndSearchesYouTube() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setQuery("abc")
        assertThat(vm.uiState.value.isShowingSuggestions).isTrue()
        advanceTimeBy(600)

        coVerify(exactly = 1) { repository.getSuggestions("abc") }
        coVerify(exactly = 1) { repository.search(SearchTab.TRACKS, "abc") }
        coVerify(exactly = 0) { jellyfin.search(any(), any()) }
        assertThat(vm.uiState.value.status).isEqualTo(SearchStatus.RESULTS)
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test
    fun modeChange_clearsResultsAndClampsTabWithoutRerunning() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setTab(SearchTab.PLAYLISTS)
        vm.setQuery("abc")
        advanceTimeBy(600)
        assertThat(vm.uiState.value.results).isNotEmpty()

        mode.value = true

        val state = vm.uiState.value
        assertThat(state.jellyfin).isTrue()
        assertThat(state.tab).isEqualTo(SearchTab.TRACKS)
        assertThat(state.results).isEmpty()
        assertThat(state.status).isEqualTo(SearchStatus.IDLE)
        coVerify(exactly = 0) { jellyfin.search(any(), any()) }
    }
}
