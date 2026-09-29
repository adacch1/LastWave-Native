package com.lastwave.app.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.playlist.PlaylistPublicMirror
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupRepositoryJellyfinTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: BackupRepository

    private val jellyfinKeys = listOf(
        "jellyfin_server_url", "jellyfin_user_id", "jellyfin_user_name",
        "jellyfin_access_token", "jellyfin_device_id",
    )
    private val theme = stringPreferencesKey("theme")

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "b.preferences_pb") }
        val mirror = mockk<PlaylistPublicMirror>()
        coEvery { mirror.writeFromDatabase() } returns Result.success(Unit)
        repo = BackupRepository(store, mockk<SavedPlaylistDao>(relaxed = true), mockk<RecommendationExclusionDao>(relaxed = true), mirror)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun signIn() = store.edit { p ->
        jellyfinKeys.forEach { p[stringPreferencesKey(it)] = "v-$it" }
        p[booleanPreferencesKey("jellyfin_mode")] = true
        p[theme] = "dark"
    }

    private suspend fun jellyfinValues() = store.data.first().let { p ->
        jellyfinKeys.associateWith { p[stringPreferencesKey(it)] }
    }

    @Test
    fun export_leavesTheJellyfinSignInOut() = runBlocking {
        signIn()
        val json = repo.buildBackup("test")
        assertThat(json).doesNotContain("jellyfin_access_token")
        assertThat(json).doesNotContain("jellyfin_device_id")
        assertThat(json).doesNotContain("v-jellyfin_access_token")
        assertThat(json).contains("theme")
    }

    @Test
    fun rollbackSnapshot_keepsTheJellyfinSignIn() = runBlocking {
        signIn()
        assertThat(repo.buildBackup("rollback", includeJellyfin = true)).contains("jellyfin_access_token")
    }

    @Test
    fun restore_keepsTheSignInWhetherOrNotSessionIsPreserved() = runBlocking {
        signIn()
        val backup = repo.buildBackup("test")
        val before = jellyfinValues()
        listOf(false, true).forEach { preserve ->
            store.edit { it[theme] = "changed" }
            assertThat(repo.restore(backup, preserveSignedInSession = preserve))
                .isInstanceOf(RestoreResult.Success::class.java)
            assertThat(jellyfinValues()).isEqualTo(before)
            assertThat(store.data.first()[theme]).isEqualTo("dark")
        }
    }

    @Test
    fun restore_ignoresJellyfinKeysInsideTheFile() = runBlocking {
        signIn()
        val oldBuildBackup = repo.buildBackup("test", includeJellyfin = true)
        store.edit { it.clear() }
        assertThat(repo.restore(oldBuildBackup, preserveSignedInSession = false))
            .isInstanceOf(RestoreResult.Success::class.java)
        assertThat(jellyfinValues().values.filterNotNull()).isEmpty()
        assertThat(store.data.first()[theme]).isEqualTo("dark")
    }
}
