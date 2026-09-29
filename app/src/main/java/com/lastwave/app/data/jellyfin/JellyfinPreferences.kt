package com.lastwave.app.data.jellyfin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class JellyfinConnection(
    val serverUrl: String = "",
    val userId: String = "",
    val userName: String = "",
    val accessToken: String = "",
) {
    val isConnected: Boolean
        get() = serverUrl.isNotBlank() && userId.isNotBlank() && accessToken.isNotBlank()

    companion object {
        val DISCONNECTED = JellyfinConnection()
    }
}

/**
 * DataStore-backed Jellyfin session. Only the access token is stored, never
 * the password: the server issues the token at login and revokes it on logout.
 */
@Singleton
class JellyfinPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val connection: Flow<JellyfinConnection> = dataStore.data
        .recoverPreferences(TAG)
        .map { prefs ->
            JellyfinConnection(
                serverUrl = prefs.readSafely(SERVER_URL_KEY).orEmpty(),
                userId = prefs.readSafely(USER_ID_KEY).orEmpty(),
                userName = prefs.readSafely(USER_NAME_KEY).orEmpty(),
                accessToken = prefs.readSafely(ACCESS_TOKEN_KEY).orEmpty(),
            )
        }

    /** True when the app is in Jellyfin mode. Requires a session, so a restored
     *  flag without a token reads false. Deduplicated because every write to the
     *  shared store re-emits. */
    val mode: Flow<Boolean> = dataStore.data
        .recoverPreferences(TAG)
        .map { it.readSafely(MODE_KEY) == true && !it.readSafely(ACCESS_TOKEN_KEY).isNullOrBlank() }
        .distinctUntilChanged()

    suspend fun setMode(on: Boolean) {
        dataStore.edit { prefs -> prefs[MODE_KEY] = on }
    }

    /** Play your Jellyfin copy of a YouTube song when one matches. On by default. */
    val preferCopies: Flow<Boolean> = dataStore.data
        .recoverPreferences(TAG)
        .map { it.readSafely(PREFER_COPIES_KEY) != false }
        .distinctUntilChanged()

    suspend fun setPreferCopies(on: Boolean) {
        dataStore.edit { prefs -> prefs[PREFER_COPIES_KEY] = on }
    }

    /** Stable per-install id. Jellyfin keys sessions by device, so a new id
     *  on every login would pile up stale devices on the server dashboard. */
    suspend fun deviceId(): String {
        dataStore.data.recoverPreferences(TAG).first().readSafely(DEVICE_ID_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        var id = ""
        dataStore.edit { prefs ->
            id = prefs.readSafely(DEVICE_ID_KEY)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID_KEY] = it }
        }
        return id
    }

    suspend fun saveConnection(connection: JellyfinConnection, mode: Boolean = false) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL_KEY] = connection.serverUrl
            prefs[USER_ID_KEY] = connection.userId
            prefs[USER_NAME_KEY] = connection.userName
            prefs[ACCESS_TOKEN_KEY] = connection.accessToken
            if (mode) prefs[MODE_KEY] = true
        }
    }

    suspend fun clearConnection() {
        dataStore.edit { prefs ->
            // Keep the server address so reconnecting only needs credentials.
            prefs.remove(USER_ID_KEY)
            prefs.remove(USER_NAME_KEY)
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(MODE_KEY)
        }
    }

    private companion object {
        const val TAG = "JellyfinPreferences"
        val SERVER_URL_KEY = stringPreferencesKey("jellyfin_server_url")
        val USER_ID_KEY = stringPreferencesKey("jellyfin_user_id")
        val USER_NAME_KEY = stringPreferencesKey("jellyfin_user_name")
        val ACCESS_TOKEN_KEY = stringPreferencesKey("jellyfin_access_token")
        val DEVICE_ID_KEY = stringPreferencesKey("jellyfin_device_id")
        val MODE_KEY = booleanPreferencesKey("jellyfin_mode")
        val PREFER_COPIES_KEY = booleanPreferencesKey("jellyfin_prefer_copies")
    }
}
