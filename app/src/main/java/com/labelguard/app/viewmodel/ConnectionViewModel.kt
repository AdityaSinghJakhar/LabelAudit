package com.labelguard.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labelguard.app.data.remote.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Checking : ConnectionState
    data class Connected(val version: String, val serverTime: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

class ConnectionViewModel : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun checkConnection() {
        viewModelScope.launch {
            _state.value = ConnectionState.Checking
            _state.value = try {
                val health = ApiClient.service.getHealth()
                ConnectionState.Connected(
                    version = health.version,
                    serverTime = health.timestamp
                )
            } catch (e: Exception) {
                ConnectionState.Failed(e.message ?: "Could not reach the backend")
            }
        }
    }
}
