package com.basira.app.service

import android.content.Context
import com.basira.core.coroutines.ApplicationScope
import com.basira.domain.assistant.AssistantEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Starts the foreground service while the user keeps a glasses session open and stops it afterwards. */
@Singleton
class SessionServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: AssistantEngine,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    /** Begins observing the engine. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            engine.state.map { it.sessionActive }.distinctUntilChanged().drop(1).collect { active ->
                if (active) AssistantForegroundService.start(context) else AssistantForegroundService.stop(context)
            }
        }
    }
}
