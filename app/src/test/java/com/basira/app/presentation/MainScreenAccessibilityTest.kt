package com.basira.app.presentation

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.basira.app.R
import com.basira.app.presentation.main.MainScreen
import com.basira.app.presentation.main.MainTestTags
import com.basira.app.presentation.main.MainUiAction
import com.basira.app.presentation.main.MainUiState
import com.basira.app.presentation.main.MainUiStateMapper
import com.basira.app.presentation.theme.BasiraTheme
import com.basira.domain.assistant.AssistantPhase
import com.basira.domain.assistant.AssistantState
import com.basira.domain.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class MainScreenAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val actions = mutableListOf<MainUiAction>()

    private fun stateFor(phase: AssistantPhase, screenReader: Boolean = false): MainUiState =
        MainUiStateMapper.map(AssistantState(phase = phase), "", screenReader, simulatedGlasses = false, fakeVision = false)

    private fun show(state: MainUiState) {
        compose.setContent { BasiraTheme { MainScreen(state, onAction = { actions += it }, onOpenSettings = {}) } }
    }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun primaryActionHasSpokenLabelAndIsAvailableWhenReady() {
        show(stateFor(AssistantPhase.Ready))

        compose.onNodeWithTag(MainTestTags.DESCRIBE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(hasText(string(R.string.action_describe)))
            .performClick()
        assertEquals(listOf(MainUiAction.Describe), actions)
    }

    @Test
    fun settingsIconHasContentDescription() {
        show(stateFor(AssistantPhase.Ready))
        compose.onNodeWithContentDescription(string(R.string.action_settings)).assertHasClickAction()
    }

    @Test
    fun statusComesBeforeThePrimaryActionInTraversalOrder() {
        show(stateFor(AssistantPhase.Ready))

        val status = compose.onNodeWithTag(MainTestTags.STATUS).fetchSemanticsNode()
        val describe = compose.onNodeWithTag(MainTestTags.DESCRIBE).fetchSemanticsNode()
        val settings = compose.onNodeWithContentDescription(string(R.string.action_settings)).fetchSemanticsNode()
        assertTrue(settings.boundsInRoot.top < status.boundsInRoot.top)
        assertTrue(status.boundsInRoot.bottom <= describe.boundsInRoot.top)
    }

    @Test
    fun busyStateDisablesDescribeWithSpokenReasonAndOffersCancel() {
        show(stateFor(AssistantPhase.Capturing(AnalysisMode.SCENE_DESCRIPTION)))

        compose.onNodeWithTag(MainTestTags.DESCRIBE)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.state_busy)))
        compose.onNodeWithTag(MainTestTags.CANCEL).performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf(MainUiAction.Cancel), actions)
    }

    @Test
    fun progressStateIsAnnouncedPolitelyWhenTalkBackIsOn() {
        show(stateFor(AssistantPhase.Analyzing(AnalysisMode.SCENE_DESCRIPTION), screenReader = true))

        val status = compose.onNodeWithTag(MainTestTags.STATUS)
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        status.assert(hasText(string(R.string.status_analyzing_title), substring = true))
        status.assert(hasText(context.getString(R.string.progress_step, 3), substring = true))
    }

    @Test
    fun statusIsNotALiveRegionWithoutTalkBackToAvoidDuplicateSpeech() {
        show(stateFor(AssistantPhase.Analyzing(AnalysisMode.SCENE_DESCRIPTION), screenReader = false))

        val node = compose.onNodeWithTag(MainTestTags.STATUS).fetchSemanticsNode()
        assertEquals(null, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun retryIsOfferedAfterATimeout() {
        show(stateFor(AssistantPhase.Timeout))

        compose.onNodeWithTag(MainTestTags.RETRY).performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf(MainUiAction.Retry), actions)
    }

    @Test
    fun setupActionIsShownForPermissionDenied() {
        show(stateFor(AssistantPhase.PermissionDenied))

        compose.onNodeWithTag(MainTestTags.SETUP).assert(hasText(string(R.string.setup_allow_camera))).performClick()
        assertTrue(actions.single() is MainUiAction.Setup)
    }

    @Test
    fun touchTargetsAreAtLeast48dp() {
        show(stateFor(AssistantPhase.Timeout))

        listOf(MainTestTags.DESCRIBE, MainTestTags.RETRY).forEach { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val minPx = 48 * context.resources.displayMetrics.density
            assertTrue("$tag too small", node.size.height >= minPx && node.size.width >= minPx)
        }
    }

    @Test
    @Config(qualifiers = "ar")
    fun arabicLocaleUsesArabicLabelsAndRightToLeftLayout() {
        var direction: LayoutDirection? = null
        compose.setContent {
            direction = LocalLayoutDirection.current
            BasiraTheme { MainScreen(stateFor(AssistantPhase.Ready), onAction = {}, onOpenSettings = {}) }
        }

        compose.onNodeWithText("صِف ما أمامي").assertIsDisplayed()
        assertEquals(LayoutDirection.Rtl, direction)
        val status = compose.onNodeWithTag(MainTestTags.STATUS).fetchSemanticsNode()
        val describe = compose.onNodeWithTag(MainTestTags.DESCRIBE).fetchSemanticsNode()
        assertTrue(status.boundsInRoot.bottom <= describe.boundsInRoot.top)
    }
}
