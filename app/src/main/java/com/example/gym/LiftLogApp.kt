package com.example.gym

import android.app.Application
import com.example.gym.data.LiftLogDatabase
import com.example.gym.data.seed.SeedImporter
import com.example.gym.sync.SyncSettingsStore
import com.example.gym.sync.TrainHubClient
import com.example.gym.sync.TrainHubSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LiftLogApp : Application() {

    val database: LiftLogDatabase by lazy { LiftLogDatabase.get(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            SeedImporter.seedIfEmpty(this@LiftLogApp, database)
        }
        // Keep TrainHubClient's cached config live so a Settings change takes effect immediately,
        // without needing to rebuild the Retrofit client.
        appScope.launch {
            SyncSettingsStore.observe(this@LiftLogApp).collect { TrainHubClient.updateConfig(it) }
        }
        TrainHubSyncWorker.schedule(this)
    }
}
