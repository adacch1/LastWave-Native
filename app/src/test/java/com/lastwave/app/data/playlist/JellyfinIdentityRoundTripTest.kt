package com.lastwave.app.data.playlist

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.StoredTrack
import com.lastwave.app.data.generate.toGenerated
import com.lastwave.app.data.generate.toStored
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import com.lastwave.app.playback.toPlayableTrack
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: youtubeVideoIdOrNull parses with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellyfinIdentityRoundTripTest {

    private fun roundTrip(track: PlayableTrack): PlayableTrack {
        val json = Json.encodeToString(listOf(track.toGeneratedTrack().toStored()))
        return Json.decodeFromString<List<StoredTrack>>(json).single().toGenerated().toPlayableTrack()
    }

    @Test
    fun jellyfinTrack_keepsIdentityThroughSavedPlaylist() {
        val restored = roundTrip(
            PlayableTrack(title = "Song", artist = "Band", album = "Album", playbackUrl = "jellyfin:abc"),
        )

        assertThat(restored.playbackUrl).isEqualTo("jellyfin:abc")
        assertThat(restored.videoId).isNull()
        assertThat(restored.title).isEqualTo("Song")
        assertThat(restored.album).isEqualTo("Album")
    }

    @Test
    fun youtubeTrack_roundTripsUnchanged() {
        val restored = roundTrip(
            PlayableTrack(title = "Song", artist = "Band", videoId = "dQw4w9WgXcQ"),
        )

        assertThat(restored.videoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(restored.playbackUrl).isNull()
        assertThat(restored.artworkUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
    }
}
