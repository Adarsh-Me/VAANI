package com.itantra.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {        super.onCreate(savedInstanceState)
        vm.initialize()
        setContent {
            ITantraTheme {
                Box(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val nav = rememberNavController()
                        val firstLaunch = vm.isFirstLaunch()
                        NavHost(
                            navController = nav,
                            startDestination = if (firstLaunch) "onboarding" else "main"
                        ) {
                            composable("onboarding") {
                                OnboardingScreen(vm) {
                                    vm.finishOnboarding()
                                    nav.navigate("main") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            }
                            composable("main") {
                                ChatScreen(vm, onNavigate = { route -> navigate(nav, route) })
                            }
                            composable("settings") {
                                SettingsScreen(vm, onNavigate = { route -> navigate(nav, route) })
                            }
                            composable("history") {
                                HistoryScreen(vm, onNavigate = { route -> navigate(nav, route) })
                            }
                            composable("connection") {
                                ConnectionScreen(vm, onNavigate = { route -> navigate(nav, route) })
                            }
                            composable("models") {
                                ModelsScreen(vm, onNavigate = { route -> navigate(nav, route) })
                            }
                        }
                    }
                    val err by vm.error.collectAsState()
                    val emergencyActive by vm.emergency.isActive.collectAsState()
                    // Mock .emg-takeover covers the whole app, not just the Talk screen.
                    if (emergencyActive) {
                        EmergencyOverlay(
                            text = vm.emergency.alertText.collectAsState().value,
                            onAck = { vm.acknowledgeEmergency() }
                        )
                    }
                    err?.let { ErrorDialog(it) { vm.clearError() } }
                }
            }
        }
    }
}

/** Bottom-nav routing: single-top, Talk ("main") stays the stack root. */
private fun navigate(nav: androidx.navigation.NavController, route: String) {
    nav.navigate(route) {
        popUpTo("main") { inclusive = false }
        launchSingleTop = true
    }
}
