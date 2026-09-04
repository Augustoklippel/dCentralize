package br.com.netscript.meshchat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/**
 * Guarda as preferências do usuário (nome de exibição e um ID de instalação
 * estável) usando Jetpack DataStore, substituindo SharedPreferences.
 */
class UserPreferences(private val context: Context) {

    private object Keys {
        val USERNAME = stringPreferencesKey("username")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val usernameFlow: Flow<String?> = context.dataStore.data.map { it[Keys.USERNAME] }

    suspend fun saveUsername(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.USERNAME] = name }
    }

    /**
     * ID único e persistente deste dispositivo na rede mesh. Gerado uma única
     * vez e reaproveitado; usado para identificar a origem de mensagens e
     * para deduplicar retransmissões.
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.firstOrNull()
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString().take(8)
        context.dataStore.edit { prefs -> prefs[Keys.DEVICE_ID] = newId }
        return newId
    }
}
