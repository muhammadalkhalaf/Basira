package com.basira.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.basira.domain.assistant.AnalysisRequestSpec
import com.basira.domain.assistant.AssistantAction
import com.basira.domain.assistant.AssistantEngine
import com.basira.domain.model.AnalysisMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Routes notification actions to the [AssistantEngine]. Not exported; only our PendingIntents reach it. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var engine: AssistantEngine

    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_DESCRIBE -> AssistantAction.Analyze(AnalysisRequestSpec(AnalysisMode.SCENE_DESCRIPTION))
            ACTION_REPEAT -> AssistantAction.Repeat
            ACTION_STOP_SPEAKING -> AssistantAction.StopSpeaking
            ACTION_END_SESSION -> AssistantAction.EndSession
            else -> return
        }
        engine.dispatch(action)
    }

    /** Intent actions. */
    companion object {
        /** Describe the scene. */
        const val ACTION_DESCRIBE: String = "com.basira.app.action.DESCRIBE"

        /** Repeat the last description. */
        const val ACTION_REPEAT: String = "com.basira.app.action.REPEAT"

        /** Stop speaking. */
        const val ACTION_STOP_SPEAKING: String = "com.basira.app.action.STOP_SPEAKING"

        /** End the glasses session. */
        const val ACTION_END_SESSION: String = "com.basira.app.action.END_SESSION"
    }
}
