package com.example.gym.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Base URL + API key for the TrainHub server. Both user-entered via the Settings screen — never hardcoded. */
data class SyncConfig(val baseUrl: String, val apiKey: String)

private val Context.syncDataStore by preferencesDataStore(name = "trainhub_sync_settings")

/**
 * Holds the TrainHub server address and API key, entered by the user in the Settings screen.
 * Backed by DataStore so it survives restarts and can be changed at any time (e.g. if the
 * Tailscale hostname/IP changes) without a rebuild.
 */
object SyncSettingsStore {
    private val KEY_BASE_URL = stringPreferencesKey("base_url")
    private val KEY_API_KEY = stringPreferencesKey("api_key")

    fun observe(context: Context): Flow<SyncConfig?> =
        context.syncDataStore.data.map { prefs ->
            val baseUrl = prefs[KEY_BASE_URL]?.trim()
            val apiKey = prefs[KEY_API_KEY]?.trim()
            if (baseUrl.isNullOrEmpty() || apiKey.isNullOrEmpty()) null else SyncConfig(baseUrl, apiKey)
        }

    suspend fun current(context: Context): SyncConfig? = observe(context).first()

    suspend fun save(context: Context, baseUrl: String, apiKey: String) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl.trim()
            prefs[KEY_API_KEY] = apiKey.trim()
        }
    }

    /** One-shot raw read for prefilling the Settings screen's text fields (empty string, not null). */
    suspend fun currentRaw(context: Context): Pair<String, String> {
        val prefs = context.syncDataStore.data.first()
        return (prefs[KEY_BASE_URL].orEmpty()) to (prefs[KEY_API_KEY].orEmpty())
    }
}
