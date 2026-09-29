package com.lastwave.app.data.jellyfin

import com.lastwave.app.data.jellyfin.JellyfinClient.Companion.toPlayableTrack
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
    fun itemJson_mapsToDirectStreamTrack() {
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
        assertEquals("https://jf.example/Audio/abc/stream?static=true&api_key=tok", track.playbackUrl)
        assertEquals("https://jf.example/Items/alb/Images/Primary?maxHeight=544&tag=t1", track.artworkUrl)
        assertNull(track.videoId)
    }

    @Test
    fun missingArtistsFallBackToAlbumArtistThenUnknown() {
        val conn = JellyfinConnection("https://jf.example", "user", "me", "tok")
        assertEquals("AA", JellyfinItem(id = "1", albumArtist = "AA").toPlayableTrack(conn).artist)
        assertEquals("Unknown artist", JellyfinItem(id = "2").toPlayableTrack(conn).artist)
    }
}
