package com.NotifyRing.app.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.NotifyRing.app.ui.call.IncomingCallActivity

class CallForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alert calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appName = intent?.getStringExtra(EXTRA_APP_NAME) ?: "NotifyRing"
        when (intent?.action) {
            ACTION_ANSWER -> LocalCallManager.answer(this, appName)
            ACTION_END -> LocalCallManager.disconnect()
            else -> {
                val notification = notification(this, appName, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_APP_NAME = "app_name"
        private const val ACTION_ANSWER = "com.NotifyRing.app.ANSWER"
        private const val ACTION_END = "com.NotifyRing.app.END"
        private const val CHANNEL_ID = "alert_calls"
        private const val NOTIFICATION_ID = 1

        fun showConnected(context: Context, appName: String) {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(context, appName, true))
        }

        private fun notification(context: Context, appName: String, connected: Boolean): android.app.Notification {
            fun actionIntent(action: String, requestCode: Int): PendingIntent =
                PendingIntent.getService(
                    context, requestCode,
                    Intent(context, CallForegroundService::class.java)
                        .setAction(action)
                        .putExtra(EXTRA_APP_NAME, appName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            val fullScreen = PendingIntent.getActivity(
                context, 0, Intent(context, IncomingCallActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val person = Person.Builder().setName(appName).setImportant(true).build()
            val end = actionIntent(ACTION_END, 2)
            val style = if (connected) {
                NotificationCompat.CallStyle.forOngoingCall(person, end)
            } else {
                NotificationCompat.CallStyle.forIncomingCall(person, end, actionIntent(ACTION_ANSWER, 1))
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentIntent(fullScreen)
                .setFullScreenIntent(fullScreen, true)
                .setStyle(style)
                .build()
        }
    }
}
