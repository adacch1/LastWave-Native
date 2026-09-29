package com.lastwave.app.data.jellyfin

import android.os.Build
import com.lastwave.app.BuildConfig
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
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
import javax.inject.Inject
import javax.inject.Singleton

class JellyfinException(message: String) : Exception(message)

/**
 * Minimal Jellyfin REST client (server 10.9+): sign-in, track search and
 * album tracks. Results map straight to [PlayableTrack] with a direct
 * `playbackUrl`, so the player streams them without the YouTube resolver.
 */
@Singleton
class JellyfinClient @Inject constructor(
    private val client: OkHttpClient,
    private val preferences: JellyfinPreferences,
) {
    val connection = preferences.connection

    /** Signs in and stores the session. The password is never persisted. */
    suspend fun login(rawServerUrl: String, username: String, password: String): Result<JellyfinConnection> =
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
                ).also { preferences.saveConnection(it) }
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
                client.newCall(request).execute().close()
            }
        }
        preferences.clearConnection()
    }

    suspend fun searchTracks(query: String, limit: Int = 50): Result<List<PlayableTrack>> =
        tracks(
            "searchTerm" to query.trim(),
            "Limit" to limit.toString(),
            "SortBy" to "SortName",
        )

    suspend fun albumTracks(albumId: String): Result<List<PlayableTrack>> =
        tracks(
            "ParentId" to albumId,
            "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
        )

    private suspend fun tracks(vararg params: Pair<String, String>): Result<List<PlayableTrack>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = connection.first()
                if (!conn.isConnected) throw JellyfinException("Connect a Jellyfin server in Settings first")
                val url = "${conn.serverUrl}/Items".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("userId", conn.userId)
                    .addQueryParameter("IncludeItemTypes", "Audio")
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
                page.items.map { it.toPlayableTrack(conn) }
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
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw JellyfinException(unauthorizedMessage)
            if (!response.isSuccessful) throw JellyfinException("Jellyfin server returned HTTP ${response.code}")
            val body = response.body?.string() ?: throw JellyfinException("Empty response from Jellyfin server")
            json.decodeFromString<T>(body)
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val TICKS_PER_MS = 10_000L

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

        internal fun JellyfinItem.toPlayableTrack(conn: JellyfinConnection): PlayableTrack {
            val artwork = when {
                imageTags["Primary"] != null -> "${conn.serverUrl}/Items/$id/Images/Primary?maxHeight=544&tag=${imageTags["Primary"]}"
                albumId != null && albumPrimaryImageTag != null -> "${conn.serverUrl}/Items/$albumId/Images/Primary?maxHeight=544&tag=$albumPrimaryImageTag"
                else -> null
            }
            return PlayableTrack(
                title = name,
                artist = artists.joinToString(", ").ifBlank { albumArtist.orEmpty() }.ifBlank { "Unknown artist" },
                album = album,
                artworkUrl = artwork,
                // static=true serves the original file untouched: no server transcode.
                playbackUrl = "${conn.serverUrl}/Audio/$id/stream?static=true&api_key=${conn.accessToken}",
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
)
