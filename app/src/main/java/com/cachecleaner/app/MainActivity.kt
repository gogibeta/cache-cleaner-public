package com.cachecleaner.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cachecleaner.app.ui.HomeScreen
import com.cachecleaner.app.ui.HomeViewModel
import com.cachecleaner.app.ui.CacheCleanerTheme
import com.cachecleaner.app.ui.WhitelistScreen
import com.cachecleaner.app.data.FileLogger

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Optional: progress notifications only. The run works regardless.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.log("ui", "MainActivity.onCreate")
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            CacheCleanerTheme {
                val nav = rememberNavController()
                // Share one ViewModel across both destinations.
                val vm: HomeViewModel = viewModel()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(vm = vm, onOpenWhitelist = { nav.navigate("whitelist") })
                    }
                    composable("whitelist") {
                        WhitelistScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FileLogger.log("ui", "MainActivity.onResume")
        // Re-check the accessibility toggle when coming back from Settings.
        // (The ViewModel's monitor loop also refreshes this periodically.)
    }

    override fun onPause() {
        super.onPause()
        FileLogger.log("ui", "MainActivity.onPause")
    }
}
