package com.labelaudit.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.labelaudit.app.ocr.OcrEngine
import com.labelaudit.app.ocr.OcrOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanState {
    data object Idle : ScanState
    data object Reading : ScanState
    data class Read(val result: OcrOutput) : ScanState
    data class Failed(val message: String) : ScanState
}

/**
 * Runs the scan pipeline on the device. Nothing here touches the network —
 * the app works with no server running and no connectivity at all.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun scan(photo: File) {
        viewModelScope.launch {
            _state.value = ScanState.Reading
            _state.value = try {
                val output = OcrEngine.recognize(getApplication(), photo)
                ScanState.Read(output)
            } catch (e: Exception) {
                ScanState.Failed(e.message ?: "Could not read the label")
            } finally {
                photo.delete()
            }
        }
    }

    fun dismiss() {
        _state.value = ScanState.Idle
    }
}
