package com.mesh.app.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.prefs.UserPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SplashUiState(
    val isLoading: Boolean = true,
    val hasNickname: Boolean? = null,
)

class SplashViewModel(
    private val userPrefs: UserPrefs,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPrefs.ensureDeviceId()
            delay(2000)
            val nickname = userPrefs.nickname.first()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasNickname = !nickname.isNullOrBlank(),
                )
            }
        }
    }
}
