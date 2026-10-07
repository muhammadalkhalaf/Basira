package com.basira.app.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.basira.app.R
import com.basira.app.presentation.main.MainScreen
import com.basira.app.presentation.main.MainViewModel
import com.basira.app.presentation.onboarding.OnboardingCallbacks
import com.basira.app.presentation.onboarding.OnboardingScreen
import com.basira.app.presentation.onboarding.OnboardingViewModel
import com.basira.app.presentation.settings.SettingsCallbacks
import com.basira.app.presentation.settings.SettingsScreen
import com.basira.app.presentation.settings.SettingsViewModel
import com.basira.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Top-level start-up state. */
sealed interface AppStartState {
    /** Settings are loading. */
    data object Loading : AppStartState

    /**
     * Settings loaded.
     *
     * @property onboardingCompleted whether the first-run setup was finished.
     */
    data class Loaded(val onboardingCompleted: Boolean) : AppStartState
}

/** Decides the start destination from persisted settings. */
@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** Start-up state. */
    val startState: StateFlow<AppStartState> = settings.settings
        .map { AppStartState.Loaded(it.onboardingCompleted && it.consentAccepted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppStartState.Loading)
}

/** Navigation routes. */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

/**
 * Root composable with the onboarding, main, and settings destinations.
 *
 * @param appViewModel start-up state holder.
 */
@Composable
fun BasiraNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val start by appViewModel.startState.collectAsStateWithLifecycle()
    when (val current = start) {
        AppStartState.Loading -> {
            val loading = stringResource(R.string.status_initializing_title)
            Box(Modifier.fillMaxSize().semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        is AppStartState.Loaded -> {
            val navController = rememberNavController()
            // Decided once; later changes are handled by explicit navigation (finish or rerun setup).
            val startDestination = remember { if (current.onboardingCompleted) Routes.MAIN else Routes.ONBOARDING }
            NavHost(navController, startDestination = startDestination) {
                composable(Routes.ONBOARDING) { OnboardingRoute(navController) }
                composable(Routes.MAIN) { MainRoute(navController) }
                composable(Routes.SETTINGS) { SettingsRoute(navController) }
            }
        }
    }
}

@Composable
private fun OnboardingRoute(navController: NavHostController) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }
    OnboardingScreen(
        state = state,
        callbacks = OnboardingCallbacks(
            onNext = viewModel::next,
            onBack = viewModel::back,
            onAcceptConsent = viewModel::acceptConsent,
            onDeclineConsent = viewModel::declineConsent,
            onResolve = viewModel::resolve,
            onTestVoice = viewModel::testVoice,
            onFinish = {
                viewModel.finish {
                    navController.navigate(Routes.MAIN) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            },
        ),
    )
}

@Composable
private fun MainRoute(navController: NavHostController) {
    val viewModel: MainViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onScreenShown() }
    MainScreen(state = state, onAction = viewModel::onAction, onOpenSettings = { navController.navigate(Routes.SETTINGS) })
}

@Composable
private fun SettingsRoute(navController: NavHostController) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        callbacks = SettingsCallbacks(
            onBack = { navController.popBackStack() },
            onLanguage = viewModel::setLanguage,
            onDetailed = viewModel::setDetailed,
            onSpeechRate = viewModel::setSpeechRate,
            onSaveHistory = viewModel::setSaveHistory,
            onSaveImages = viewModel::setSaveImages,
            onConfirmSaveImages = viewModel::confirmSaveImages,
            onMediaButton = viewModel::setMediaButton,
            onRequestDelete = viewModel::requestDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onDismissDialog = viewModel::dismissDialog,
            onToggleSession = viewModel::toggleSession,
            onRerunSetup = {
                viewModel.rerunSetup {
                    navController.navigate(Routes.ONBOARDING) { popUpTo(Routes.MAIN) { inclusive = true } }
                }
            },
        ),
    )
}
