package com.lastwave.app.data.jellyfin

import android.os.Build
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.model.AlbumPageData
import com.lastwave.app.data.model.ArtistAlbumItem
import com.lastwave.app.data.model.ArtistPageData
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class JellyfinException(message: String) : Exception(message)

/**
 * Minimal Jellyfin REST client (server 10.9+): sign-in, search, browse queries
 * and stream requests. Results map to [PlayableTrack] with a token-free
 * `jellyfin:<id>` `playbackUrl`, which the player resolves when it opens the file.
 */
@Singleton
class JellyfinClient @Inject constructor(
    private val client: OkHttpClient,
    private val preferences: JellyfinPreferences,
) {
    // ponytail: LAN server; raise if remote servers are slow
    private val http = client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).build()

    val connection = preferences.connection
    val mode = preferences.mode

    suspend fun setMode(on: Boolean) = preferences.setMode(on)

    /** Signs in and stores the session. The password is never persisted.
     *  [switchSource] also turns Jellyfin mode on in the same DataStore edit. */
    suspend fun login(
        rawServerUrl: String,
        username: String,
        password: String,
        switchSource: Boolean = false,
    ): Result<JellyfinConnection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val server = normalizeServerUrl(rawServerUrl)
                    ?: throw JellyfinException("Enter the full server address, for example http://192.168.1.10:8096")
                val body = json.encodeToString(AuthRequest(username.trim(), password))
                    .toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url("$server/Users/AuthenticateByName")
                    .header("Authorization", authorizationHeader(token = null))
                    .post(body)
                    .build()
                val auth: AuthResponse = execute(request, unauthorizedMessage = "Wrong username or password")
                JellyfinConnection(
                    serverUrl = server,
                    userId = auth.user.id,
                    userName = auth.user.name,
                    accessToken = auth.accessToken,
                ).also { preferences.saveConnection(it, mode = switchSource) }
            }
        }

    /** Revokes the token on the server (best effort) and forgets the session. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val conn = connection.first()
        if (conn.isConnected) {
            runCatching {
                val request = Request.Builder()
                    .url("${conn.serverUrl}/Sessions/Logout")
                    .header("Authorization", authorizationHeader(conn.accessToken))
                    .post(ByteArray(0).toRequestBody())
                    .build()
                http.newCall(request).execute().close()
            }
        }
        preferences.clearConnection()
    }

    /** Direct-stream URL plus the header that authorizes it, or null when signed out.
     *  The token travels only in the header, never in the URL. */
    suspend fun streamRequest(itemId: String): Pair<String, Map<String, String>>? {
        val conn = connection.first()
        if (!conn.isConnected) return null
        return "${conn.serverUrl}/Audio/$itemId/stream?static=true" to
            mapOf("Authorization" to authorizationHeader(conn.accessToken))
    }

    /** Real `Content-Type` of the stream from one `HEAD` request, or null when unknown. */
    suspend fun streamMimeType(itemId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (url, headers) = streamRequest(itemId) ?: return@runCatching null
            val request = Request.Builder().url(url).head()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.startsWith("audio/") }
            }
        }.getOrNull()
    }

    suspend fun search(tab: SearchTab, query: String): Result<List<SearchResultItem>> {
        val q = query.trim()
        // ponytail: 50 results, page when libraries outgrow it
        return when (tab) {
            SearchTab.TRACKS -> items("Audio", "searchTerm" to q, "Limit" to "50", "SortBy" to "SortName") { item, conn ->
                val track = item.toPlayableTrack(conn)
                SearchResultItem(
                    name = track.title,
                    artist = track.artist,
                    subtitle = track.album,
                    artworkUrl = track.artworkUrl,
                    entityId = ID_PREFIX + item.id,
                    track = track,
                )
            }
            // The album row shows only the subtitle, so it carries "artist · year".
            SearchTab.ALBUMS -> items("MusicAlbum", "searchTerm" to q, "Limit" to "50", "SortBy" to "SortName") { item, conn ->
                val result = item.toResult(conn)
                result.copy(subtitle = listOfNotNull(result.artist, result.subtitle).joinToString(" · ").ifBlank { null })
            }
            SearchTab.ARTISTS -> items(
                null,
                "searchTerm" to q, "Limit" to "50", "SortBy" to "SortName",
                path = "/Artists/AlbumArtists",
            ) { item, conn -> item.toResult(conn) }
            else -> Result.success(emptyList())
        }
    }

    suspend fun albums(sortBy: String, descending: Boolean = false, limit: Int = 20): Result<List<SearchResultItem>> =
        items(
            "MusicAlbum",
            "SortBy" to sortBy,
            "SortOrder" to if (descending) "Descending" else "Ascending",
            "Limit" to limit.toString(),
        ) { item, conn -> item.toResult(conn) }

    // ponytail: /Artists/AlbumArtists is deprecated in 12.x; fall back to /Items?IncludeItemTypes=MusicArtist if removed
    suspend fun artists(limit: Int = 30): Result<List<SearchResultItem>> =
        items(null, "SortBy" to "SortName", "Limit" to limit.toString(), path = "/Artists/AlbumArtists") { item, conn ->
            item.toResult(conn)
        }

    // ponytail: 200 playlists, page when libraries outgrow it
    suspend fun playlists(): Result<List<SearchResultItem>> =
        items("Playlist", "MediaTypes" to "Audio", "SortBy" to "SortName", "Limit" to "200", "Fields" to "ChildCount") { item, conn ->
            item.toResult(conn, ref = ID_PREFIX + PLAYLIST_REF + item.id)
                .copy(subtitle = item.childCount?.let { "$it tracks" })
        }

    /** Album or playlist page for a `jellyfin:` ref (the id without the prefix; playlists start with [PLAYLIST_REF]). */
    suspend fun albumPage(ref: String, title: String, artist: String): Result<AlbumPageData> {
        val server = connection.first().serverUrl
        val isPlaylist = ref.startsWith(PLAYLIST_REF)
        val id = ref.removePrefix(PLAYLIST_REF)
        // Playlist items come back in playlist order; the server doesn't filter out items you can't access, so keep Audio only.
        val loaded = if (isPlaylist) {
            items(null, path = "/Playlists/$id/Items") { item, conn -> item to item.toPlayableTrack(conn) }
                .map { list -> list.filter { it.first.type == "Audio" } }
        } else {
            items("Audio", "ParentId" to id, "SortBy" to "ParentIndexNumber,IndexNumber,SortName") { item, conn ->
                item to item.toPlayableTrack(conn)
            }
        }
        return loaded.map { rows ->
            val head = rows.firstOrNull()
            AlbumPageData(
                title = title,
                // A playlist has no single artist, so no chips render.
                artist = if (isPlaylist) "" else head?.first?.albumArtist ?: artist,
                browseId = ID_PREFIX + ref,
                // empty album keeps a Jellyfin image URL so artwork never falls back to the name lookup
                artworkUrl = head?.second?.artworkUrl ?: "$server/Items/$id/Images/Primary?maxHeight=544",
                artistBrowseId = if (isPlaylist) null else head?.first?.albumArtists?.firstOrNull()?.id?.let { ID_PREFIX + it },
                releaseYear = if (isPlaylist) null else head?.first?.productionYear?.toString(),
                trackCountText = "${rows.size} tracks",
                tracks = rows.map { it.second },
            )
        }
    }

    /** Artist page for a `jellyfin:<artistId>` ref (the id without the prefix). Bio, tags and similar artists stay empty. */
    suspend fun artistPage(artistId: String, name: String): Result<ArtistPageData> = coroutineScope {
        // ponytail: 300 cap, page when libraries outgrow it
        val songs = async {
            items("Audio", "ArtistIds" to artistId, "SortBy" to "Album,ParentIndexNumber,IndexNumber", "Limit" to "300") { item, conn ->
                item.toPlayableTrack(conn)
            }
        }
        val albums = async {
            items("MusicAlbum", "AlbumArtistIds" to artistId, "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending") { item, conn ->
                item to ArtistAlbumItem(
                    title = item.name,
                    browseId = ID_PREFIX + item.id,
                    year = item.productionYear?.toString(),
                    artworkUrl = item.imageUrl(conn),
                )
            }
        }
        val tracks = songs.await()
        val rows = albums.await()
        val server = connection.first().serverUrl
        tracks.mapCatching { top ->
            val albumRows = rows.getOrThrow()
            ArtistPageData(
                // The server name wins over the route argument.
                name = albumRows.firstNotNullOfOrNull { (item, _) -> item.albumArtists.firstOrNull { it.id == artistId }?.name } ?: name,
                browseId = ID_PREFIX + artistId,
                artworkUrl = "$server/Items/$artistId/Images/Primary?maxHeight=544",
                topSongs = top,
                albums = albumRows.map { it.second },
                fallbackArtworkUrl = albumRows.firstOrNull()?.second?.artworkUrl,
            )
        }
    }

    private suspend fun <T> items(
        type: String?,
        vararg params: Pair<String, String>,
        path: String = "/Items",
        map: (JellyfinItem, JellyfinConnection) -> T,
    ): Result<List<T>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = connection.first()
                if (!conn.isConnected) throw JellyfinException("Connect a Jellyfin server in Settings first")
                val url = "${conn.serverUrl}$path".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("userId", conn.userId)
                    .apply { if (type != null) addQueryParameter("IncludeItemTypes", type) }
                    .addQueryParameter("Recursive", "true")
                    .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", authorizationHeader(conn.accessToken))
                    .get()
                    .build()
                val page: ItemsResponse = execute(
                    request,
                    unauthorizedMessage = "Jellyfin session expired. Sign in again in Settings.",
                )
                page.items.map { map(it, conn) }
            }
        }

    private suspend fun authorizationHeader(token: String?): String {
        val parts = mutableListOf(
            "Client=\"LastWave\"",
            "Device=\"${headerSafe(Build.MODEL ?: "Android")}\"",
            "DeviceId=\"${headerSafe(preferences.deviceId())}\"",
            "Version=\"${headerSafe(BuildConfig.VERSION_NAME)}\"",
        )
        if (token != null) parts += "Token=\"${headerSafe(token)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    private inline fun <reified T> execute(request: Request, unauthorizedMessage: String): T =
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw JellyfinException(unauthorizedMessage)
            if (!response.isSuccessful) throw JellyfinException("Jellyfin server returned HTTP ${response.code}")
            val body = response.body?.string() ?: throw JellyfinException("Empty response from Jellyfin server")
            json.decodeFromString<T>(body)
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val TICKS_PER_MS = 10_000L

        /** Prefix of the token-free identity: `jellyfin:<itemId>`. */
        const val ID_PREFIX = "jellyfin:"

        /** Follows [ID_PREFIX] in a playlist browse id: `jellyfin:playlist:<id>`. */
        const val PLAYLIST_REF = "playlist:"

        /** The item id when [v] is a `jellyfin:` identity, otherwise null. */
        fun itemIdOf(v: String?): String? = v?.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

        fun isImageUrl(u: String?): Boolean = u?.contains("/Images/Primary") == true

        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        /** Returns `scheme://host[:port][/path]` without a trailing slash, or
         *  null when the input isn't an http(s) URL. Keeps reverse-proxy sub-paths. */
        internal fun normalizeServerUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.contains("://")) return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            return url.toString().trimEnd('/')
        }

        /** OkHttp rejects non-ASCII header values; device names can contain them. */
        private fun headerSafe(value: String): String =
            value.filter { it in ' '..'~' && it != '"' }

        internal fun mimeTypeForContainer(container: String?): String? =
            when (container?.substringBefore(',')?.lowercase()) {
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "m4a", "mp4", "aac", "alac" -> "audio/mp4"
                "ogg", "oga", "opus" -> "audio/ogg"
                "webm", "webma" -> "audio/webm"
                "wav" -> "audio/wav"
                else -> null // ExoPlayer sniffs the container
            }

        /** Never null: an item without art gets the bare URL, which the server 404s,
         *  so the UI shows its fallback icon instead of a name-based artwork lookup. */
        internal fun JellyfinItem.imageUrl(conn: JellyfinConnection): String = when {
            imageTags["Primary"] != null -> "${conn.serverUrl}/Items/$id/Images/Primary?maxHeight=544&tag=${imageTags["Primary"]}"
            albumId != null && albumPrimaryImageTag != null -> "${conn.serverUrl}/Items/$albumId/Images/Primary?maxHeight=544&tag=$albumPrimaryImageTag"
            else -> "${conn.serverUrl}/Items/$id/Images/Primary?maxHeight=544"
        }

        internal fun JellyfinItem.toResult(conn: JellyfinConnection, ref: String = ID_PREFIX + id): SearchResultItem =
            SearchResultItem(
                name = name,
                artist = albumArtist,
                subtitle = productionYear?.toString(),
                artworkUrl = imageUrl(conn),
                entityId = ref,
            )

        internal fun JellyfinItem.toPlayableTrack(conn: JellyfinConnection): PlayableTrack {
            return PlayableTrack(
                title = name,
                artist = artists.joinToString(", ").ifBlank { albumArtist.orEmpty() }.ifBlank { "Unknown artist" },
                album = album,
                artworkUrl = imageUrl(conn),
                // Token-free identity; the player resolves it (static=true, no transcode) when the file opens.
                playbackUrl = ID_PREFIX + id,
                playbackMimeType = mimeTypeForContainer(container),
                durationMs = runTimeTicks?.div(TICKS_PER_MS),
            )
        }
    }
}

@Serializable
internal data class AuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
internal data class AuthResponse(
    @SerialName("User") val user: JellyfinUser,
    @SerialName("AccessToken") val accessToken: String,
)

@Serializable
internal data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

@Serializable
internal data class ItemsResponse(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
)

@Serializable
internal data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameId> = emptyList(),
)

@Serializable
internal data class NameId(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)
