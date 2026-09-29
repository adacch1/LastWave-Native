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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        every { prefs.preferCopies } returns flowOf(true)
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
        every { prefs.preferCopies } returns flowOf(true)

        assertThat(JellyfinClient(OkHttpClient(), prefs).streamRequest("abc")).isNull()
    }

    /** A client whose HTTP layer answers each request URL with the JSON that [body] returns. */
    private fun clientServing(body: (String) -> String): JellyfinClient {
        val prefs = mockk<JellyfinPreferences>()
        every { prefs.connection } returns flowOf(JellyfinConnection("https://jf.example", "user", "me", "tok"))
        every { prefs.mode } returns flowOf(false)
        every { prefs.preferCopies } returns flowOf(true)
        coEvery { prefs.deviceId() } returns "dev1"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(request.url.toString()).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return JellyfinClient(http, prefs)
    }

    @Test
    fun albumPage_setsArtistBrowseIdFromAlbumArtists() = runBlocking {
        val client = clientServing {
            """{"Items":[{"Id":"t1","Name":"Song","Album":"Record","AlbumArtist":"AC/DC","ProductionYear":1980,
              "AlbumArtists":[{"Id":"art","Name":"AC/DC"}],"ImageTags":{}}]}"""
        }

        val page = client.albumPage("alb", "Record", "AC").getOrThrow()

        assertThat(page.artistBrowseId).isEqualTo("jellyfin:art")
        assertThat(page.artist).isEqualTo("AC/DC")
    }

    @Test
    fun albumPage_withoutAlbumArtistsHasNoArtistBrowseId() = runBlocking {
        val client = clientServing { """{"Items":[{"Id":"t1","Name":"Song","ImageTags":{}}]}""" }

        assertThat(client.albumPage("alb", "Record", "A").getOrThrow().artistBrowseId).isNull()
    }

    @Test
    fun artistPage_mapsTracksAlbumsAndServerName() = runBlocking {
        val client = clientServing { url ->
            if ("IncludeItemTypes=MusicAlbum" in url) {
                """{"Items":[{"Id":"alb1","Name":"Later","ProductionYear":1979,"ImageTags":{"Primary":"t1"},
                  "AlbumArtists":[{"Id":"art","Name":"Earth, Wind & Fire"}]}]}"""
            } else {
                """{"Items":[{"Id":"s1","Name":"September","Artists":["Earth, Wind & Fire"],"ImageTags":{}}]}"""
            }
        }

        val page = client.artistPage("art", "Earth").getOrThrow()

        assertThat(page.name).isEqualTo("Earth, Wind & Fire")
        assertThat(page.browseId).isEqualTo("jellyfin:art")
        assertThat(page.artworkUrl).isEqualTo("https://jf.example/Items/art/Images/Primary?maxHeight=544")
        assertThat(page.topSongs.single().playbackUrl).isEqualTo("jellyfin:s1")
        assertThat(page.albums.single().browseId).isEqualTo("jellyfin:alb1")
        assertThat(page.albums.single().year).isEqualTo("1979")
        assertThat(page.fallbackArtworkUrl).isEqualTo(page.albums.single().artworkUrl)
    }

    @Test
    fun artistPage_keepsRouteNameWhenAlbumsCarryNoArtistIds() = runBlocking {
        val client = clientServing { """{"Items":[]}""" }

        assertThat(client.artistPage("art", "Route name").getOrThrow().name).isEqualTo("Route name")
    }

    @Test
    fun artists_mapToJellyfinArtistIdentity() = runBlocking {
        var requested = ""
        val client = clientServing { url ->
            requested = url
            """{"Items":[{"Id":"art","Name":"AC/DC","ImageTags":{"Primary":"p"}}]}"""
        }

        val artist = client.artists().getOrThrow().single()

        assertThat(artist.entityId).isEqualTo("jellyfin:art")
        assertThat(artist.name).isEqualTo("AC/DC")
        assertThat(requested).contains("/Artists/AlbumArtists")
        assertThat(client.search(com.lastwave.app.data.search.SearchTab.ARTISTS, "ac").getOrThrow().single().entityId)
            .isEqualTo("jellyfin:art")
        assertThat(requested).contains("searchTerm=ac")
    }

    @Test
    fun playlists_mapToPlaylistRefsWithTrackCount() = runBlocking {
        var requested = ""
        val client = clientServing { url ->
            requested = url
            """{"Items":[{"Id":"pl1","Name":"Road trip","ChildCount":12,"Type":"Playlist","ImageTags":{"Primary":"p"}},
              {"Id":"pl2","Name":"Empty","ImageTags":{}}]}"""
        }

        val (first, second) = client.playlists().getOrThrow()

        assertThat(first.entityId).isEqualTo("jellyfin:playlist:pl1")
        assertThat(first.name).isEqualTo("Road trip")
        assertThat(first.subtitle).isEqualTo("12 tracks")
        assertThat(first.artworkUrl).isEqualTo("https://jf.example/Items/pl1/Images/Primary?maxHeight=544&tag=p")
        assertThat(second.subtitle).isNull()
        assertThat(requested).contains("IncludeItemTypes=Playlist")
        assertThat(requested).contains("MediaTypes=Audio")
        assertThat(requested).contains("Fields=ChildCount")
    }

    @Test
    fun albumPage_playlistRefKeepsServerOrderAndDropsNonAudio() = runBlocking {
        var requested = ""
        val client = clientServing { url ->
            requested = url
            """{"Items":[{"Id":"t2","Name":"Second","AlbumArtist":"X","Type":"Audio","ImageTags":{}},
              {"Id":"v1","Name":"Video","Type":"Video","ImageTags":{}},
              {"Id":"t1","Name":"First","AlbumArtist":"X","AlbumArtists":[{"Id":"art","Name":"X"}],"Type":"Audio","ImageTags":{}}]}"""
        }

        val page = client.albumPage("playlist:pl1", "Road trip", "").getOrThrow()

        assertThat(requested).contains("/Playlists/pl1/Items")
        assertThat(page.browseId).isEqualTo("jellyfin:playlist:pl1")
        assertThat(page.tracks.map { it.playbackUrl }).containsExactly("jellyfin:t2", "jellyfin:t1").inOrder()
        assertThat(page.trackCountText).isEqualTo("2 tracks")
        assertThat(page.artist).isEmpty()
        assertThat(page.artistBrowseId).isNull()
    }

    private fun song(id: String, name: String, artists: List<String>, seconds: Long?) =
        JellyfinItem(id = id, name = name, artists = artists, runTimeTicks = seconds?.let { it * 10_000_000L })

    @Test
    fun bestCopy_matchesSameRecordingIgnoringVideoNoise() {
        val items = listOf(song("a", "Blinding Lights", listOf("The Weeknd"), 200))
        assertThat(JellyfinClient.bestCopy(items, "Blinding Lights (Official Video)", "The Weeknd", 201_500)?.id).isEqualTo("a")
    }

    @Test
    fun bestCopy_matchesEachArtistOfACollaboration() {
        val items = listOf(song("a", "Levitating", listOf("Dua Lipa", "DaBaby"), 203))
        assertThat(JellyfinClient.bestCopy(items, "Levitating", "DaBaby & Dua Lipa", 203_000)?.id).isEqualTo("a")
    }

    @Test
    fun bestCopy_rejectsUnaskedLiveTakeWrongArtistAndLengthMismatch() {
        assertThat(JellyfinClient.bestCopy(listOf(song("a", "Yellow (Live)", listOf("Coldplay"), 266)), "Yellow", "Coldplay", 266_000)).isNull()
        assertThat(JellyfinClient.bestCopy(listOf(song("a", "Yellow", listOf("Someone Else"), 266)), "Yellow", "Coldplay", 266_000)).isNull()
        assertThat(JellyfinClient.bestCopy(listOf(song("a", "Yellow", listOf("Coldplay"), 280)), "Yellow", "Coldplay", 266_000)).isNull()
    }

    @Test
    fun bestCopy_withoutDurationNeedsExactTitleAndArtist() {
        assertThat(JellyfinClient.bestCopy(listOf(song("a", "Yellow", listOf("Coldplay"), null)), "Yellow", "Coldplay", null)?.id).isEqualTo("a")
        assertThat(JellyfinClient.bestCopy(listOf(song("a", "Yellow Song", listOf("Coldplay"), null)), "Yellow", "Coldplay", null)).isNull()
    }

    @Test
    fun bestCopy_prefersClosestDuration() {
        val items = listOf(song("far", "Yellow", listOf("Coldplay"), 269), song("near", "Yellow", listOf("Coldplay"), 266))
        assertThat(JellyfinClient.bestCopy(items, "Yellow", "Coldplay", 266_200)?.id).isEqualTo("near")
    }
}
