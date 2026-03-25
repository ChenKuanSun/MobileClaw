package ai.affiora.mobileclaw.ui.devices

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class PairedDevice(
    val id: String,
    val name: String,
    val lastSeen: Long,
)

data class DevicesUiState(
    val pairedDevices: List<PairedDevice> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class DevicesViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()
}
