package com.example.gym.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gym.sync.SyncSettingsStore
import com.example.gym.sync.TrainHubSyncWorker
import kotlinx.coroutines.launch

/**
 * Server URL + API key for TrainHub sync. Both are stored locally via DataStore (see
 * SyncSettingsStore) — never hardcoded, never committed — so this screen is the one place either
 * value is entered or changed (e.g. if the Tailscale address moves).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (savedUrl, savedKey) = SyncSettingsStore.currentRaw(context)
        baseUrl = savedUrl
        apiKey = savedKey
        loaded = true
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp),
            )
            Text("TrainHub Sync", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        HorizontalDivider()

        if (loaded) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Syncs logged workouts to your TrainHub server over Tailscale. " +
                        "Leave blank to disable syncing.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://<tailscale-hostname>:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Standalone — doesn't touch the fields above, so this is safe to tap any
                    // time you've just turned Tailscale on and want to flush the sync queue
                    // without re-entering the URL/key.
                    OutlinedButton(
                        onClick = {
                            TrainHubSyncWorker.requestImmediateSync(context)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Sync requested",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Sync now") }
                    Button(
                        onClick = {
                            scope.launch {
                                SyncSettingsStore.save(context, baseUrl, apiKey)
                                TrainHubSyncWorker.requestImmediateSync(context)
                                snackbarHostState.showSnackbar(
                                    message = "Saved",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Save") }
                }
            }
        }
    }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
