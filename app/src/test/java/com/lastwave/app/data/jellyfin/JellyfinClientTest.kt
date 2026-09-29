package com.lastwave.app.data.jellyfin

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.jellyfin.JellyfinClient.Companion.imageUrl
import com.lastwave.app.data.jellyfin.JellyfinClient.Companion.toPlayableTrack
import com.lastwave.app.data.jellyfin.JellyfinClient.Companion.toResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinClientTest {

    @Test
    fun normalizeServerUrl_keepsSubPathAndDropsTrailingSlash() {
        assertEquals("http://192.168.1.10:8096", JellyfinClient.normalizeServerUrl(" http://192.168.1.10:8096/ "))
        assertEquals("https://example.com/jellyfin", JellyfinClient.normalizeServerUrl("https://example.com/jellyfin/"))
    }

    @Test
    fun normalizeServerUrl_rejectsMissingOrUnsupportedScheme() {
        assertNull(JellyfinClient.normalizeServerUrl("192.168.1.10:8096"))
        assertNull(JellyfinClient.normalizeServerUrl("ftp://example.com"))
        assertNull(JellyfinClient.normalizeServerUrl(""))
    }

    @Test
    fun itemJson_mapsToJellyfinIdentityTrack() {
        val page = JellyfinClient.json.decodeFromString(
            ItemsResponse.serializer(),
            """
            {"Items":[{"Id":"abc","Name":"Song","Album":"Record","AlbumId":"alb",
              "Artists":["A","B"],"RunTimeTicks":2400000000,"Container":"flac",
              "ImageTags":{},"AlbumPrimaryImageTag":"t1","Type":"Audio"}],
             "TotalRecordCount":1}
            """.trimIndent(),
        )
        val conn = JellyfinConnection("https://jf.example", "user", "me", "tok")
        val track = page.items.single().toPlayableTrack(conn)

        assertEquals("A, B", track.artist)
        assertEquals(240_000L, track.durationMs)
        assertEquals("audio/flac", track.playbackMimeType)
        assertEquals("jellyfin:abc", track.playbackUrl)
        assertThat(track.playbackUrl).doesNotContain("tok")
        assertEquals("https://jf.example/Items/alb/Images/Primary?maxHeight=544&tag=t1", track.artworkUrl)
        assertNull(track.videoId)
    }

    @Test
    fun missingArtistsFallBackToAlbumArtistThenUnknown() {
        val conn = JellyfinConnection("https://jf.example", "user", "me", "tok")
        assertEquals("AA", JellyfinItem(id = "1", albumArtist = "AA").toPlayableTrack(conn).artist)
        assertEquals("Unknown artist", JellyfinItem(id = "2").toPlayableTrack(conn).artist)
    }

    @Test
    fun itemIdOf_returnsIdOnlyForJellyfinIdentity() {
        assertThat(JellyfinClient.itemIdOf("jellyfin:abc")).isEqualTo("abc")
        assertThat(JellyfinClient.itemIdOf("jellyfin:playlist:p1")).isEqualTo("playlist:p1")
        assertThat(JellyfinClient.itemIdOf("http://jf.example/a")).isNull()
        assertThat(JellyfinClient.itemIdOf("content://media/1")).isNull()
        assertThat(JellyfinClient.itemIdOf("/sdcard/x")).isNull()
        assertThat(JellyfinClient.itemIdOf(null)).isNull()
    }

    @Test
    fun isImageUrl_matchesPrimaryImagePath() {
        assertThat(JellyfinClient.isImageUrl("https://jf.example/Items/a/Images/Primary?maxHeight=544")).isTrue()
        assertThat(JellyfinClient.isImageUrl("https://i.ytimg.com/vi/x/hq.jpg")).isFalse()
        assertThat(JellyfinClient.isImageUrl(null)).isFalse()
    }

    @Test
    fun albumJson_mapsToJellyfinAlbumResult() {
        val page = JellyfinClient.json.decodeFromString(
            ItemsResponse.serializer(),
            """
            {"Items":[{"Id":"alb","Name":"Record","AlbumArtist":"AC/DC","ProductionYear":1980,
              "ChildCount":10,"Type":"MusicAlbum","ImageTags":{"Primary":"t9"},
              "AlbumArtists":[{"Id":"art","Name":"AC/DC"}]}]}
            """.trimIndent(),
        )
        val conn = JellyfinConnection("https://jf.example", "user", "me", "tok")
        val result = page.items.single().toResult(conn)

        assertThat(result.entityId).isEqualTo("jellyfin:alb")
        assertThat(result.artist).isEqualTo("AC/DC")
        assertThat(result.subtitle).isEqualTo("1980")
        assertThat(result.artworkUrl).isEqualTo("https://jf.example/Items/alb/Images/Primary?maxHeight=544&tag=t9")
        assertThat(page.items.single().albumArtists.single().id).isEqualTo("art")
    }

    @Test
    fun itemWithoutImageTags_stillGetsArtworkUrl() {
        val conn = JellyfinConnection("https://jf.example", "user", "me", "tok")
        assertThat(JellyfinItem(id = "alb").imageUrl(conn))
            .isEqualTo("https://jf.example/Items/alb/Images/Primary?maxHeight=544")
    }

    @Test
    fun streamRequest_keepsTokenOutOfUrlAndInAuthorizationHeader() = runBlocking {
        val prefs = mockk<JellyfinPreferences>()
        every { prefs.connection } returns flowOf(JellyfinConnection("https://jf.example", "user", "me", "tok"))
        every { prefs.mode } returns flowOf(false)
        coEvery { prefs.deviceId() } returns "dev1"

        val (url, headers) = JellyfinClient(OkHttpClient(), prefs).streamRequest("abc")!!

        assertThat(url).isEqualTo("https://jf.example/Audio/abc/stream?static=true")
        assertThat(url).doesNotContain("api_key")
        assertThat(headers["Authorization"]).contains("Token=\"tok\"")
    }

    @Test
    fun streamRequest_isNullWhenSignedOut() = runBlocking {
        val prefs = mockk<JellyfinPreferences>()
        every { prefs.connection } returns flowOf(JellyfinConnection.DISCONNECTED)
        every { prefs.mode } returns flowOf(false)

        assertThat(JellyfinClient(OkHttpClient(), prefs).streamRequest("abc")).isNull()
    }
}
