package com.basira.app

import android.app.Application
import com.basira.app.core.BuildModes
import com.basira.app.data.glasses.DatSdkInitializer
import com.basira.app.data.speech.AudioRouteManager
import com.basira.app.service.AssistantNotifications
import com.basira.app.service.SessionServiceController
import com.basira.core.coroutines.ApplicationScope
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.repository.GlassesRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Initializes the Meta Wearables Device Access Toolkit here (when the Bluetooth permission is
 * already granted; otherwise right after the user grants it), starts the app-scoped assistant engine,
 * and wires the foreground-service controller.
 */
@HiltAndroidApp
class BasiraApplication : Application() {

    @Inject lateinit var datSdk: DatSdkInitializer

    @Inject lateinit var engine: AssistantEngine

    @Inject lateinit var sessionServiceController: SessionServiceController

    @Inject lateinit var glasses: GlassesRepository

    @Inject lateinit var audioRoutes: AudioRouteManager

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        AssistantNotifications.createChannel(this)
        if (BuildModes.glassesMode != "fake") datSdk.initializeIfPermitted()
        engine.start()
        sessionServiceController.start()
        scope.launch {
            glasses.status.map { it.device?.name }.distinctUntilChanged().collect(audioRoutes::setGlassesName)
        }
    }
}
