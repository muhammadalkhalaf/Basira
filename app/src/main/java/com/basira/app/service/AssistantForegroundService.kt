package com.basira.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.basira.core.coroutines.ApplicationScope
import com.basira.core.logging.AppLogger
import com.basira.core.reporting.ErrorDomain
import com.basira.core.reporting.ErrorReport
import com.basira.core.reporting.ErrorReporter
import com.basira.core.reporting.ErrorSeverity
import com.basira.domain.assistant.AssistantEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type `connectedDevice`) that keeps the glasses session usable while the app is
 * in the background, shows the persistent accessible notification, and hosts the optional media
 * button session. It owns no business logic; everything is delegated to [AssistantEngine].
 *
 * Started and stopped by [SessionServiceController] when the user starts or ends a session.
 */
@AndroidEntryPoint
class AssistantForegroundService : Service() {

    @Inject
    lateinit var engine: AssistantEngine

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var logger: AppLogger

    @Inject
    lateinit var errorReporter: ErrorReporter

    private var observer: Job? = null
    private var mediaButtons: MediaButtonController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AssistantNotifications.createChannel(this)
        val notification = AssistantNotifications.build(this, engine.state.value.phase)
        try {
            ServiceCompat.startForeground(
                this,
                AssistantNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: RuntimeException) {
            // For example ForegroundServiceStartNotAllowedException when started from the background.
            errorReporter.report(
                ErrorReport(
                    domain = ErrorDomain.SERVICE,
                    operation = "service.startForeground",
                    severity = ErrorSeverity.CRITICAL,
                    outcome = "service_stopped",
                    throwable = e,
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) observer = scope.launch { observe() }
        return START_NOT_STICKY
    }

    /** Mirrors the engine into the notification and media session until the service is destroyed. */
    private suspend fun observe() = coroutineScope {
        launch { observeMediaButtonSetting() }
        engine.state.map { it.phase }.distinctUntilChanged().collect { phase ->
            val service = this@AssistantForegroundService
            if (NotificationManagerCompat.from(service).areNotificationsEnabled()) {
                try {
                    NotificationManagerCompat.from(service)
                        .notify(AssistantNotifications.NOTIFICATION_ID, AssistantNotifications.build(service, phase))
                } catch (e: SecurityException) {
                    errorReporter.report(
                        ErrorReport(
                            domain = ErrorDomain.SERVICE,
                            operation = "notification.update",
                            severity = ErrorSeverity.WARNING,
                            outcome = "notification_stale",
                            throwable = e,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun observeMediaButtonSetting() {
        engine.state.map { it.settings.mediaButtonEnabled }.distinctUntilChanged().collect { enabled ->
            if (enabled) {
                val controller = mediaButtons ?: MediaButtonController(this@AssistantForegroundService, engine, logger)
                mediaButtons = controller
                controller.enable()
            } else {
                mediaButtons?.disable()
            }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        mediaButtons?.disable()
        mediaButtons = null
        super.onDestroy()
    }

    /** Start and stop helpers. */
    companion object {
        /**
         * Starts the service. Must be called while the app is in the foreground.
         *
         * @param context any context.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AssistantForegroundService::class.java))
        }

        /**
         * Stops the service.
         *
         * @param context any context.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantForegroundService::class.java))
        }
    }
}
