package com.basira.app.presentation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.basira.app.data.accessibility.AccessibilityStateProvider
import com.basira.app.localization.SetupAction
import com.basira.app.presentation.main.MainUiAction
import com.basira.app.presentation.main.MainViewModel
import com.basira.app.presentation.setup.ActivityEffect
import com.basira.app.presentation.setup.ActivityEffectBus
import com.basira.app.presentation.setup.PermissionStatusProvider
import com.basira.app.testutil.TestEngine
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Verbosity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engineScope: CoroutineScope
    private lateinit var fixture: TestEngine
    private lateinit var effects: ActivityEffectBus
    private lateinit var viewModel: MainViewModel
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engineScope = CoroutineScope(SupervisorJob() + dispatcher)
        fixture = TestEngine(engineScope)
        effects = ActivityEffectBus()
        viewModel = MainViewModel(fixture.engine, effects, PermissionStatusProvider(context), AccessibilityStateProvider(context))
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `describe walks from ready through capture to loaded`() = runTest(dispatcher) {
        viewModel.uiState.test {
            advanceUntilIdle()
            assertEquals(AssistantPhase.Ready, expectMostRecentItem().phase)
            val gate = CompletableDeferred<Unit>()
            fixture.glasses.captureGate = gate

            viewModel.onAction(MainUiAction.Describe)
            advanceUntilIdle()
            val capturing = expectMostRecentItem()
            assertEquals(AssistantPhase.Capturing(AnalysisMode.SCENE_DESCRIPTION), capturing.phase)
            assertFalse(capturing.canDescribe)
            assertTrue(capturing.canCancel)

            gate.complete(Unit)
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertEquals(AssistantPhase.Loaded, loaded.phase)
            assertTrue(loaded.canRepeat)
            assertTrue(loaded.lastDescription!!.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `duplicate taps start a single capture`() = runTest(dispatcher) {
        advanceUntilIdle()
        fixture.glasses.captureGate = CompletableDeferred()

        repeat(3) { viewModel.onAction(MainUiAction.Describe) }
        advanceUntilIdle()

        assertEquals(1, fixture.glasses.captureCount)
    }

    @Test
    fun `cancel returns to ready and keeps nothing running`() = runTest(dispatcher) {
        viewModel.uiState.test {
            advanceUntilIdle()
            fixture.glasses.captureGate = CompletableDeferred()
            viewModel.onAction(MainUiAction.Describe)
            advanceUntilIdle()
            viewModel.onAction(MainUiAction.Cancel)
            advanceUntilIdle()
            assertEquals(AssistantPhase.Ready, expectMostRecentItem().phase)
            assertTrue(fixture.vision.requests.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `find uses the typed object name`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.onAction(MainUiAction.FindTextChanged("النظارة الشمسية"))
        viewModel.onAction(MainUiAction.Find)
        advanceUntilIdle()

        assertEquals("النظارة الشمسية", fixture.vision.requests.single().targetObject)
    }

    @Test
    fun `voice command without microphone permission asks the activity`() = runTest(dispatcher) {
        shadowOf(context).denyPermissions(android.Manifest.permission.RECORD_AUDIO)

        viewModel.onAction(MainUiAction.VoiceCommand)

        assertEquals(ActivityEffect.RequestMicrophoneThenListen, effects.effects.first())
    }

    @Test
    fun `setup actions are forwarded as activity effects`() = runTest(dispatcher) {
        viewModel.onAction(MainUiAction.Setup(SetupAction.ALLOW_CAMERA))

        assertEquals(ActivityEffect.Setup(SetupAction.ALLOW_CAMERA), effects.effects.first())
    }

    @Test
    fun `toggle detailed persists the verbosity`() = runTest(dispatcher) {
        viewModel.uiState.test {
            advanceUntilIdle()
            viewModel.onAction(MainUiAction.ToggleDetailed)
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().detailed)
            assertEquals(Verbosity.DETAILED, fixture.settings.current.verbosity)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `screen shown opens the glasses session once`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.onScreenShown()
        advanceUntilIdle()
        viewModel.onScreenShown()
        advanceUntilIdle()

        assertEquals(1, fixture.glasses.startSessionCalls)
    }
}
