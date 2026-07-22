package com.mesh.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPrefs(private val context: Context) {
    private val nicknameKey = stringPreferencesKey("nickname")
    private val deviceIdKey = stringPreferencesKey("device_id")

    val nickname: Flow<String?> =
        context.userPrefsDataStore.data.map { it[nicknameKey] }

    val deviceId: Flow<String> =
        context.userPrefsDataStore.data.map { prefs ->
            prefs[deviceIdKey] ?: ""
        }

    suspend fun setNickname(value: String) {
        context.userPrefsDataStore.edit { it[nicknameKey] = value.trim() }
    }

    suspend fun ensureDeviceId(): String {
        val existing = context.userPrefsDataStore.data.first()[deviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        context.userPrefsDataStore.edit { it[deviceIdKey] = newId }
        return newId
    }
}
