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
import com.example.gym.ui.HistoryScreen
import com.example.gym.ui.TreeScreen
import com.example.gym.ui.theme.GymTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GymTheme {
                var historyId by rememberSaveable { mutableStateOf(-1L) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (historyId < 0) {
                        TreeScreen(
                            onOpenHistory = { historyId = it },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else {
                        BackHandler { historyId = -1L }
                        HistoryScreen(
                            setRowId = historyId,
                            onBack = { historyId = -1L },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}
