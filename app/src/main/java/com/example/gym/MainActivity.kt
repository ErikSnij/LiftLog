package com.example.gym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.gym.ui.BodyWeightScreen
import com.example.gym.ui.HistoryScreen
import com.example.gym.ui.ProgressScreen
import com.example.gym.ui.SettingsScreen
import com.example.gym.ui.TreeScreen
import com.example.gym.ui.theme.GymTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GymTheme {
                var historyId by rememberSaveable { mutableStateOf(-1L) }
                var showBodyWeight by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showProgress by rememberSaveable { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when {
                        showSettings -> {
                            BackHandler { showSettings = false }
                            SettingsScreen(
                                onBack = { showSettings = false },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        showBodyWeight -> {
                            BackHandler { showBodyWeight = false }
                            BodyWeightScreen(
                                onBack = { showBodyWeight = false },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        historyId >= 0 -> {
                            BackHandler { historyId = -1L }
                            HistoryScreen(
                                exerciseId = historyId,
                                onBack = { historyId = -1L },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        // Below History, so an exercise's history opened from Progress returns here.
                        // Progress handles Back itself: it zooms out before it closes.
                        showProgress -> {
                            ProgressScreen(
                                onBack = { showProgress = false },
                                onOpenHistory = { historyId = it },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        else -> {
                            TreeScreen(
                                onOpenHistory = { historyId = it },
                                onOpenBodyWeight = { showBodyWeight = true },
                                onOpenSettings = { showSettings = true },
                                onOpenProgress = { showProgress = true },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }
                }
            }
        }
    }
}
