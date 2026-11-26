package com.notifyring.app.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.notifyring.app.R
import com.notifyring.app.ui.call.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Foreground service (`foregroundServiceType="phoneCall"`) hosting the ongoing incoming/active
 * `CallStyle` notification and coordinating [CallRingtoneController].
 *
 * Started **after** `CallsManager.addCall` registers the Telecom call (API 34/35 requirement).
 */
class CallForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ringtoneController: CallRingtoneController
    private var stateCollectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ringtoneController = CallRingtoneController(applicationContext)
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedCallId = intent?.getStringExtra(EXTRA_CALL_ID)?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
        }
        val fallbackAppName = intent?.getStringExtra(EXTRA_APP_NAME)
            ?: getString(R.string.app_name)

        val manager = LocalCallManager.getInstance(applicationContext)
        val currentState = manager.sessionState.value

        val resolvedSession: Pair<UUID, AlertPayload>? = when (currentState) {
            is CallSessionState.Ringing -> {
                if (requestedCallId == null || currentState.callId == requestedCallId) {
                    currentState.callId to currentState.payload
                } else {
                    null
                }
            }
            is CallSessionState.Active -> {
                if (requestedCallId == null || currentState.callId == requestedCallId) {
                    currentState.callId to currentState.payload
                } else {
                    null
                }
            }
            CallSessionState.Idle, is CallSessionState.Ended -> null
        }

        val isStopRequested = intent?.action == ACTION_STOP_CALL_SERVICE
        val isConnected = currentState is CallSessionState.Active ||
            intent?.action == ACTION_UPDATE_CONNECTED

        val notificationCallId = resolvedSession?.first ?: requestedCallId
        val notificationPayload = resolvedSession?.second ?: AlertPayload(
            appName = fallbackAppName,
            packageName = "",
            sender = "",
            message = ""
        )

        // Always satisfy Android's 5-second FGS contract immediately on every onStartCommand entry.
        val notification = buildCallNotification(
            context = this,
            callId = notificationCallId,
            payload = notificationPayload,
            connected = isConnected
        )
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to start phoneCall FGS: ${e.javaClass.simpleName}")
            ringtoneController.stopRinging()
            stopSelf()
            return START_NOT_STICKY
        }

        // If LocalCallManager has no active call matching callId (or stop was requested),
        // immediately tear down foreground state so ongoing notifications can never stick.
        if (resolvedSession == null || isStopRequested) {
            ringtoneController.stopRinging()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (isConnected) {
            ringtoneController.stopRinging()
        } else {
            ringtoneController.startRinging()
        }

        ensureStateObserver(manager, resolvedSession.first)
        return START_NOT_STICKY
    }

    private fun ensureStateObserver(manager: LocalCallManager, boundCallId: UUID) {
        stateCollectorJob?.cancel()
        stateCollectorJob = serviceScope.launch {
            manager.sessionState.collect { state ->
                when (state) {
                    is CallSessionState.Ringing -> {
                        if (state.callId != boundCallId) {
                            stopForegroundCleanly()
                        } else {
                            ringtoneController.startRinging()
                            notifyIfPermitted(
                                this@CallForegroundService,
                                buildCallNotification(
                                    context = this@CallForegroundService,
                                    callId = state.callId,
                                    payload = state.payload,
                                    connected = false
                                )
                            )
                        }
                    }
                    is CallSessionState.Active -> {
                        if (state.callId != boundCallId) {
                            stopForegroundCleanly()
                        } else {
                            ringtoneController.stopRinging()
                            notifyIfPermitted(
                                this@CallForegroundService,
                                buildCallNotification(
                                    context = this@CallForegroundService,
                                    callId = state.callId,
                                    payload = state.payload,
                                    connected = true
                                )
                            )
                        }
                    }
                    CallSessionState.Idle, is CallSessionState.Ended -> {
                        stopForegroundCleanly()
                    }
                }
            }
        }
    }

    private fun stopForegroundCleanly() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        ringtoneController.stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        if (::ringtoneController.isInitialized) {
            ringtoneController.stopRinging()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotifyRingCallFgs"

        const val EXTRA_CALL_ID = "com.notifyring.app.extra.CALL_ID"
        const val EXTRA_AUTO_ANSWER = "com.notifyring.app.extra.AUTO_ANSWER"
        const val EXTRA_APP_NAME = "app_name"

        const val ACTION_START_RINGING = "com.notifyring.app.ACTION_START_RINGING"
        const val ACTION_UPDATE_CONNECTED = "com.notifyring.app.ACTION_UPDATE_CONNECTED"
        const val ACTION_STOP_CALL_SERVICE = "com.notifyring.app.ACTION_STOP_CALL_SERVICE"

        const val CHANNEL_ID = "alert_calls"
        const val NOTIFICATION_ID = 1

        private const val REQUEST_CODE_FULL_SCREEN = 100
        private const val REQUEST_CODE_ANSWER = 101
        private const val REQUEST_CODE_DECLINE = 102
        private const val REQUEST_CODE_END = 103

        fun showConnected(context: Context, callId: UUID, payload: AlertPayload) {
            val appContext = context.applicationContext
            ensureNotificationChannel(appContext)
            notifyIfPermitted(
                appContext,
                buildCallNotification(
                    context = appContext,
                    callId = callId,
                    payload = payload,
                    connected = true
                )
            )
        }

        fun stopForCall(context: Context, callId: UUID) {
            val appContext = context.applicationContext
            val stopIntent = Intent(appContext, CallForegroundService::class.java)
                .putExtra(EXTRA_CALL_ID, callId.toString())
            val stopped = appContext.stopService(stopIntent)
            if (!stopped) {
                appContext.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            }
        }

        fun canUseFullScreenIntent(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.getSystemService(NotificationManager::class.java)
                    ?.canUseFullScreenIntent() ?: false
            } else {
                true
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_calls_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                // Ringing sound and vibration are managed by CallRingtoneController so they stop
                // cleanly on Answer without re-chiming during connected CallStyle updates.
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        private fun notifyIfPermitted(context: Context, notification: Notification) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            if (!manager.areNotificationsEnabled()) return
            manager.notify(NOTIFICATION_ID, notification)
        }

        internal fun buildCallNotification(
            context: Context,
            callId: UUID?,
            payload: AlertPayload,
            connected: Boolean
        ): Notification {
            val callIdString = callId?.toString()

            val fullScreenIntent = Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    if (callIdString != null) putExtra(EXTRA_CALL_ID, callIdString)
                    putExtra(EXTRA_AUTO_ANSWER, false)
                }
            val fullScreenPi = PendingIntent.getActivity(
                context,
                REQUEST_CODE_FULL_SCREEN,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val answerIntent = Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    if (callIdString != null) putExtra(EXTRA_CALL_ID, callIdString)
                    putExtra(EXTRA_AUTO_ANSWER, true)
                }
            val answerPi = PendingIntent.getActivity(
                context,
                REQUEST_CODE_ANSWER,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .apply {
                    if (callIdString != null) putExtra(EXTRA_CALL_ID, callIdString)
                }
            val declinePi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_DECLINE,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val endIntent = Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_END)
                .apply {
                    if (callIdString != null) putExtra(EXTRA_CALL_ID, callIdString)
                }
            val endPi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_END,
                endIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val displayLabel = when {
                payload.appName.isNotBlank() && payload.sender.isNotBlank() ->
                    "${payload.appName}: ${payload.sender}"
                payload.sender.isNotBlank() -> payload.sender
                payload.appName.isNotBlank() -> payload.appName
                else -> context.getString(R.string.app_name)
            }
            val person = Person.Builder()
                .setName(displayLabel)
                .setImportant(true)
                .build()

            val callStyle = if (connected) {
                NotificationCompat.CallStyle.forOngoingCall(person, endPi)
            } else {
                NotificationCompat.CallStyle.forIncomingCall(person, declinePi, answerPi)
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (connected) android.R.drawable.sym_call_outgoing
                    else android.R.drawable.sym_call_incoming
                )
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(fullScreenPi)
                .setStyle(callStyle)

            // Attach setFullScreenIntent ONLY while Ringing — never when connected!
            if (!connected) {
                builder.setFullScreenIntent(fullScreenPi, true)
            }

            return builder.build()
        }
    }
}
