package com.notifyring.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notifyring.app.di.AppContainer
import com.notifyring.app.ui.home.SeniorHomeScreen
import com.notifyring.app.ui.onboarding.OnboardingWizardScreen
import com.notifyring.app.ui.settings.CaregiverSettingsScreen
import com.notifyring.app.ui.settings.VoiceSpeechScreen
import kotlinx.coroutines.flow.first

object NotifyRingRoutes {
    const val ONBOARDING = "onboarding"
    const val SENIOR_HOME = "senior_home"
    const val CAREGIVER_SETTINGS = "caregiver_settings"
    const val VOICE_SPEECH = "voice_speech"
}

@Composable
fun NotifyRingNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(container) {
        val initialSettings = container.settingsRepository.settings.first()
        startDestination = if (initialSettings.onboardingCompleted) {
            NotifyRingRoutes.SENIOR_HOME
        } else {
            NotifyRingRoutes.ONBOARDING
        }
    }

    val resolvedStart = startDestination
    if (resolvedStart == null) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = resolvedStart,
        modifier = modifier
    ) {
        composable(NotifyRingRoutes.ONBOARDING) {
            OnboardingWizardScreen(
                settingsRepository = container.settingsRepository,
                localCallManager = container.localCallManager,
                onOpenVoiceSpeech = {
                    navController.navigate(NotifyRingRoutes.VOICE_SPEECH) {
                        launchSingleTop = true
                    }
                },
                onOnboardingComplete = {
                    navController.navigate(NotifyRingRoutes.SENIOR_HOME) {
                        popUpTo(NotifyRingRoutes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(NotifyRingRoutes.SENIOR_HOME) {
            SeniorHomeScreen(
                settingsRepository = container.settingsRepository,
                alertCallLogStore = container.alertCallLogStore,
                localCallManager = container.localCallManager,
                onOpenCaregiverSettings = {
                    navController.navigate(NotifyRingRoutes.CAREGIVER_SETTINGS) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(NotifyRingRoutes.CAREGIVER_SETTINGS) {
            CaregiverSettingsScreen(
                settingsRepository = container.settingsRepository,
                alertCallLogStore = container.alertCallLogStore,
                onNavigateBack = { navController.popBackStack() },
                onOpenVoiceSpeech = {
                    navController.navigate(NotifyRingRoutes.VOICE_SPEECH) {
                        launchSingleTop = true
                    }
                },
                onRerunOnboarding = {
                    navController.navigate(NotifyRingRoutes.ONBOARDING) {
                        popUpTo(NotifyRingRoutes.SENIOR_HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(NotifyRingRoutes.VOICE_SPEECH) {
            VoiceSpeechScreen(
                settingsRepository = container.settingsRepository,
                voiceCatalogProvider = container.voiceCatalogProvider,
                callSpeechEngine = container.callSpeechEngine,
                ttsSettingsBridge = container.ttsSettingsBridge,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
