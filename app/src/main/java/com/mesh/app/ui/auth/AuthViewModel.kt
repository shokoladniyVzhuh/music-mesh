package com.mesh.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.prefs.UserPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val nickname: String = "",
)

class AuthViewModel(
    private val userPrefs: UserPrefs,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onNicknameChange(value: String) {
        _uiState.update { it.copy(nickname = value) }
    }

    fun saveNickname(onSaved: () -> Unit) {
        val nickname = _uiState.value.nickname.trim()
        if (nickname.isEmpty()) return
        viewModelScope.launch {
            userPrefs.setNickname(nickname)
            onSaved()
        }
    }
}
