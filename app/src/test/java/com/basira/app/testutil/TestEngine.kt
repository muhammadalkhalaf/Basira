package com.basira.app.testutil

import com.basira.core.reporting.NoOpErrorReporter
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.fakes.FakeAppLanguageRepository
import com.basira.domain.fakes.FakeConnectivityObserver
import com.basira.domain.fakes.FakeGlassesRepositoryForTest
import com.basira.domain.fakes.FakeHistoryRepository
import com.basira.domain.fakes.FakeImageArchive
import com.basira.domain.fakes.FakePhoneLocator
import com.basira.domain.fakes.FakeSettingsRepository
import com.basira.domain.fakes.FakeSpeechOutput
import com.basira.domain.fakes.FakeVisionRepository
import com.basira.domain.fakes.FakeVoiceRecognizer
import com.basira.domain.fakes.RecordingAnnouncer
import com.basira.domain.fakes.RecordingFeedbackPlayer
import com.basira.domain.usecase.AnalyzeSurroundingsUseCase
import com.basira.domain.usecase.DescribeSceneUseCase
import com.basira.domain.usecase.DescriptionValidator
import com.basira.domain.usecase.FindObjectUseCase
import com.basira.domain.usecase.IdentifyCurrencyUseCase
import com.basira.domain.usecase.ReadTextUseCase
import com.basira.domain.voice.VoiceCommandParser
import kotlinx.coroutines.CoroutineScope

/** Engine wired to fakes, for ViewModel tests. */
class TestEngine(scope: CoroutineScope) {
    val glasses = FakeGlassesRepositoryForTest()
    val vision = FakeVisionRepository()
    val speech = FakeSpeechOutput()
    val announcer = RecordingAnnouncer(speech)
    val settings = FakeSettingsRepository()
    val history = FakeHistoryRepository()
    val archive = FakeImageArchive()
    val voice = FakeVoiceRecognizer()
    val appLanguage = FakeAppLanguageRepository()
    private var ids = 0

    private val analyze = AnalyzeSurroundingsUseCase(
        glasses, vision, FakeConnectivityObserver(), archive, { "req-${++ids}" }, { 0L }, DescriptionValidator(), appLanguage,
    )

    val engine = AssistantEngine(
        glasses = glasses,
        speech = speech,
        announcer = announcer,
        feedback = RecordingFeedbackPlayer(),
        connectivity = FakeConnectivityObserver(),
        settingsRepository = settings,
        history = history,
        voiceRecognizer = voice,
        voiceParser = VoiceCommandParser(),
        phoneLocator = FakePhoneLocator(),
        describeScene = DescribeSceneUseCase(analyze),
        readText = ReadTextUseCase(analyze),
        findObject = FindObjectUseCase(analyze),
        identifyCurrency = IdentifyCurrencyUseCase(analyze),
        scope = scope,
        errorReporter = NoOpErrorReporter,
    ).also { it.start() }
}
