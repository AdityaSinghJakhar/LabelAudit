package com.labelaudit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labelaudit.app.data.remote.ScanAccepted
import com.labelaudit.app.data.repository.ScanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanState {
    data object Idle : ScanState
    data object Uploading : ScanState
    data class Uploaded(val result: ScanAccepted) : ScanState
    data class Failed(val message: String) : ScanState
}

class ScanViewModel(
    private val repository: ScanRepository = ScanRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun upload(image: File) {
        viewModelScope.launch {
            _state.value = ScanState.Uploading
            _state.value = repository.uploadScan(image).fold(
                onSuccess = { ScanState.Uploaded(it) },
                onFailure = { ScanState.Failed(it.message ?: "Upload failed") }
            )
        }
    }

    fun dismiss() {
        _state.value = ScanState.Idle
    }
}
