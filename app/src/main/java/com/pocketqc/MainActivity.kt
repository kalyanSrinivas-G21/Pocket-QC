package com.pocketqc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pocketqc.inference.InferenceBackendManager
import com.pocketqc.settings.SettingsManager
import com.pocketqc.ui.navigation.PocketQcNavHost
import com.pocketqc.ui.navigation.Screen
import com.pocketqc.ui.theme.ObsidianBg
import com.pocketqc.ui.theme.PocketQcTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InferenceBackendManager.configureEnvironmentForAdsp(applicationInfo.nativeLibraryDir)
        val startRoute = intent?.getStringExtra("start_route") ?: Screen.Home.route
        setContent {
            val settingsManager = remember { SettingsManager(this) }
            val currentSettings by settingsManager.settings.collectAsState()
            PocketQcTheme(themeMode = currentSettings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBg
                ) {
                    PocketQcNavHost(
                        settingsManager = settingsManager,
                        startDestination = startRoute
                    )
                }
            }
        }
    }
}
