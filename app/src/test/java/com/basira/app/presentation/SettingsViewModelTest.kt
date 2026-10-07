package com.basira.app.presentation

import com.basira.app.presentation.settings.SettingsDialog
import com.basira.app.presentation.settings.SettingsViewModel
import com.basira.app.testutil.TestEngine
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.Confidence
import com.basira.domain.model.SceneDescription
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var fixture: TestEngine
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        fixture = TestEngine(scope)
        viewModel = SettingsViewModel(fixture.settings, fixture.history, fixture.archive, fixture.engine)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `saving photos requires explicit confirmation`() = runTest(dispatcher) {
        viewModel.setSaveImages(true)
        advanceUntilIdle()
        assertFalse(fixture.settings.current.saveImages)
        assertEquals(SettingsDialog.CONFIRM_SAVE_IMAGES, viewModel.uiState.first { it.dialog != null }.dialog)

        viewModel.confirmSaveImages()
        advanceUntilIdle()
        assertTrue(fixture.settings.current.saveImages)
    }

    @Test
    fun `delete local history clears stored descriptions after confirmation`() = runTest(dispatcher) {
        fixture.history.add(SceneDescription("r", "نص", Confidence.HIGH, emptyList(), null, AnalysisMode.READ_TEXT, null, 0))
        viewModel.requestDelete()
        advanceUntilIdle()
        assertEquals(1, fixture.history.history.first().size)

        viewModel.confirmDelete()
        advanceUntilIdle()
        assertTrue(fixture.history.history.first().isEmpty())
    }

    @Test
    fun `turning history off deletes it`() = runTest(dispatcher) {
        fixture.history.add(SceneDescription("r", "نص", Confidence.HIGH, emptyList(), null, AnalysisMode.READ_TEXT, null, 0))
        viewModel.setSaveHistory(false)
        advanceUntilIdle()
        assertTrue(fixture.history.history.first().isEmpty())
    }
}
