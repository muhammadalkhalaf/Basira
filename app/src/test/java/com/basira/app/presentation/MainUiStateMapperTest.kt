package com.basira.app.presentation

import com.basira.app.R
import com.basira.app.localization.SetupAction
import com.basira.app.presentation.main.MainUiStateMapper
import com.basira.app.presentation.main.StatusTone
import com.basira.core.error.AppError
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.assistant.AssistantState
import com.basira.domain.model.AnalysisMode
import com.basira.domain.model.AudioRoute
import com.basira.domain.model.Confidence
import com.basira.domain.model.SceneDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateMapperTest {

    private fun map(state: AssistantState, screenReader: Boolean = false) =
        MainUiStateMapper.map(state, "", screenReader, simulatedGlasses = false, fakeVision = false)

    private val description = SceneDescription("r", "باب", Confidence.LOW, emptyList(), null, AnalysisMode.SCENE_DESCRIPTION, null, 0)

    @Test
    fun `ready enables the main action and hides cancel`() {
        val ui = map(AssistantState(phase = AssistantPhase.Ready))
        assertTrue(ui.canDescribe)
        assertFalse(ui.canCancel)
        assertFalse(ui.canRetry)
        assertEquals(StatusTone.SUCCESS, ui.tone)
        assertEquals(R.string.status_ready_title, ui.statusText.title)
    }

    @Test
    fun `busy phases disable describe, offer cancel and report progress steps`() {
        listOf(
            AssistantPhase.Capturing(AnalysisMode.SCENE_DESCRIPTION) to 1,
            AssistantPhase.Uploading(AnalysisMode.SCENE_DESCRIPTION) to 2,
            AssistantPhase.Analyzing(AnalysisMode.SCENE_DESCRIPTION) to 3,
        ).forEach { (phase, step) ->
            val ui = map(AssistantState(phase = phase))
            assertFalse(ui.canDescribe)
            assertTrue(ui.canCancel)
            assertEquals(step, ui.progressStep)
            assertEquals(StatusTone.PROGRESS, ui.tone)
        }
    }

    @Test
    fun `retryable failures offer retry and keep the last description`() {
        val ui = map(AssistantState(phase = AssistantPhase.Timeout, lastDescription = description))
        assertTrue(ui.canRetry)
        assertTrue(ui.canRepeat)
        assertEquals("باب", ui.lastDescription)
        assertEquals(R.string.confidence_low, ui.lastConfidence)
        assertEquals(StatusTone.ERROR, ui.tone)
    }

    @Test
    fun `setup phases expose a recovery action instead of retry`() {
        assertEquals(SetupAction.ALLOW_CAMERA, map(AssistantState(phase = AssistantPhase.PermissionDenied)).setupAction)
        assertEquals(SetupAction.CONNECT_META_AI, map(AssistantState(phase = AssistantPhase.RegistrationRequired)).setupAction)
        assertEquals(SetupAction.INSTALL_META_AI, map(AssistantState(phase = AssistantPhase.MetaAiMissing)).setupAction)
        assertEquals(SetupAction.INSTALL_VOICE, map(AssistantState(phase = AssistantPhase.SpeechUnavailable(true))).setupAction)
        assertFalse(map(AssistantState(phase = AssistantPhase.PermissionDenied)).canRetry)
        assertNull(map(AssistantState(phase = AssistantPhase.FatalError(AppError.Forbidden))).setupAction)
    }

    @Test
    fun `live region is used only while a screen reader is active`() {
        assertTrue(map(AssistantState(), screenReader = true).statusLiveRegion)
        assertFalse(map(AssistantState(), screenReader = false).statusLiveRegion)
    }

    @Test
    fun `audio route label`() {
        assertEquals(R.string.route_glasses, map(AssistantState(audioRoute = AudioRoute.GLASSES)).audioRouteLabel)
        assertEquals(R.string.route_phone, map(AssistantState(audioRoute = AudioRoute.PHONE_SPEAKER)).audioRouteLabel)
    }

    @Test
    fun `rate limit detail carries the wait in seconds`() {
        val ui = map(AssistantState(phase = AssistantPhase.RateLimited(4_200)))
        assertEquals(R.plurals.status_rate_limited_seconds_detail, ui.statusText.detail)
        assertTrue(ui.statusText.detailIsPlural)
        assertEquals(5, ui.statusText.detailArgument)
    }
}
