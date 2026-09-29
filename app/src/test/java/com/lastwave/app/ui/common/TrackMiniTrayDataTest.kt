package com.lastwave.app.ui.common

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.PlayableTrack
import org.junit.Test

class TrackMiniTrayDataTest {

    @Test
    fun toPlayable_prefersSuppliedPlayable() {
        val jf = PlayableTrack(title = "T", artist = "A", playbackUrl = "jellyfin:abc")
        val data = TrackMiniTrayData(title = "T", artist = "A", videoId = "yt1", playable = jf)
        assertThat(data.toPlayable()).isEqualTo(jf)
    }

    @Test
    fun toPlayable_buildsFromFieldsWhenNoPlayable() {
        val p = TrackMiniTrayData(title = "T", artist = "A", album = "Al", videoId = "yt1").toPlayable()
        assertThat(p.videoId).isEqualTo("yt1")
        assertThat(p.playbackUrl).isNull()
    }
}
