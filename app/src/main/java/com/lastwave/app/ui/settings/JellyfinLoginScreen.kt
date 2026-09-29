package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.jellyfin.JellyfinClient
import com.lastwave.app.data.jellyfin.JellyfinConnection
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Jellyfin sign-in state, shared by first-launch onboarding and Settings. */
@HiltViewModel
class JellyfinLoginViewModel @Inject constructor(
    private val jellyfinClient: JellyfinClient,
) : ViewModel() {
    val connection: StateFlow<JellyfinConnection> = jellyfinClient.connection
        .catch { emit(JellyfinConnection.DISCONNECTED) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JellyfinConnection.DISCONNECTED)

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        if (_connecting.value) return
        _connecting.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                jellyfinClient.login(serverUrl, username, password).onFailure { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.e("JellyfinLogin", "Jellyfin login failed", error)
                    _error.value = error.message ?: "Couldn't connect to Jellyfin"
                }
            } finally {
                _connecting.value = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { jellyfinClient.logout() }.onFailure { error ->
                if (error is CancellationException) throw error
                _error.value = "Couldn't disconnect Jellyfin. Try again."
            }
        }
    }
}

/** Standalone Jellyfin sign-in, reached from the first-launch Login screen. */
@Composable
fun JellyfinLoginScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: JellyfinLoginViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Login's gate treats a Jellyfin session as onboarded, so popping back
    // lets it forward straight to MainShell.
    LaunchedEffect(connection.isConnected) {
        if (connection.isConnected) onConnected()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp)
                .imePadding(),
        ) {
            ExpressiveHeader(
                title = "Connect Jellyfin",
                subtitle = "Stream music from your own server",
                onBack = onBack,
            )
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                JellyfinIntegrationCard(
                    connection = connection,
                    connecting = connecting,
                    error = error,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }
}
