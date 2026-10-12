package com.phoenix.droidvault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.phoenix.droidvault.ui.screens.MainScreen
import com.phoenix.droidvault.ui.theme.DroidVaultTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()

            val isDark = when (themeMode) {
                1 -> false
                2, 3, 4 -> true
                else -> systemDark
            }

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = this@MainActivity.window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
                }
            }

            DroidVaultTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }

        // Launch effects (ZIP open → flash wizard) only on a REAL launch.
        // On config-driven recreation (system theme change) savedInstanceState
        // is non-null and getIntent() still returns the ORIGINAL launch
        // intent, so the unguarded call re-opened the flash wizard on every
        // theme toggle. onNewIntent keeps handling deep links while the task
        // is alive (fresh intent, no saved state involved).
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the activity's intent so a later getIntent() returns THIS
        // one. Without it, onNewIntent's intent was handled but the stored
        // intent stayed the original, so a config-driven recreation would
        // re-handle the stale launch intent.
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Deep link from the run notification: reopen the live Processing
        // screen for a run left in the background (or its finished result).
        if (intent.getBooleanExtra(EXTRA_SHOW_PROGRESS, false)) {
            com.phoenix.droidvault.ui.screens.ProcessingScreenController.requestShow()
            return
        }
        val action = intent.action
        val data = intent.data ?: return
        val type = intent.type

        if (action == Intent.ACTION_VIEW) {
            val path = data.path?.lowercase() ?: ""
            if (path.endsWith(".zip") || type == "application/zip") {
                viewModel.setTab(1) // Flash destination in the dashboard-first shell
                viewModel.openFlashWizard()
                viewModel.processSelectedZips(listOf(data), append = true)
            }
        }
    }

    companion object {
        /** Intent extra set by run notifications' content intent. */
        const val EXTRA_SHOW_PROGRESS = "com.phoenix.droidvault.SHOW_PROGRESS"
    }
}
