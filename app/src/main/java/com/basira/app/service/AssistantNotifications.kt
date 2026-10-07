package com.basira.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.basira.app.MainActivity
import com.basira.app.R
import com.basira.app.localization.PhaseStrings
import com.basira.domain.assistant.AssistantPhase

/**
 * Builds the persistent, accessible session notification.
 *
 * The title and text are plain sentences (no icons-only meaning) so TalkBack reads them fully, and the
 * actions mirror the main in-app controls: describe, repeat, stop speaking, end session.
 */
object AssistantNotifications {

    /** Channel for the ongoing session notification. */
    const val CHANNEL_ID: String = "assistant_session"

    /** Notification id of the foreground service. */
    const val NOTIFICATION_ID: Int = 42

    /**
     * Creates the notification channel. Safe to call repeatedly.
     *
     * @param context any context.
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Builds the notification for [phase].
     *
     * @param context any context.
     * @param phase current assistant phase.
     * @return the notification.
     */
    fun build(context: Context, phase: AssistantPhase): Notification {
        val text = PhaseStrings.of(phase)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(text.title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notification_action_describe), action(context, NotificationActionReceiver.ACTION_DESCRIBE))
            .addAction(0, context.getString(R.string.notification_action_repeat), action(context, NotificationActionReceiver.ACTION_REPEAT))
            .addAction(0, context.getString(R.string.notification_action_stop), action(context, NotificationActionReceiver.ACTION_STOP_SPEAKING))
            .addAction(0, context.getString(R.string.notification_action_end), action(context, NotificationActionReceiver.ACTION_END_SESSION))
            .build()
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
