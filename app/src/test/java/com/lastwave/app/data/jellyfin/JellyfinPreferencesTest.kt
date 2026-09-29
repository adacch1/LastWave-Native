package com.lastwave.app.data.jellyfin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
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

class JellyfinPreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: JellyfinPreferences

    private val connection = JellyfinConnection("http://h:8096", "u1", "me", "tok")

    @Before
    fun setUp() {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "jf.preferences_pb") }
        prefs = JellyfinPreferences(store)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun mode_needsASession() = runBlocking {
        prefs.setMode(true)
        assertThat(prefs.mode.first()).isFalse()
    }

    @Test
    fun saveConnection_staysInYouTubeModeUnlessAsked() = runBlocking {
        prefs.saveConnection(connection)
        assertThat(prefs.mode.first()).isFalse()
        prefs.saveConnection(connection, mode = true)
        assertThat(prefs.mode.first()).isTrue()
    }

    @Test
    fun clearConnection_switchesBackToYouTube() = runBlocking {
        prefs.saveConnection(connection, mode = true)
        prefs.clearConnection()
        assertThat(prefs.mode.first()).isFalse()
        // A later sign-in from Settings doesn't resurrect the old flag.
        prefs.saveConnection(connection)
        assertThat(prefs.mode.first()).isFalse()
    }
}
