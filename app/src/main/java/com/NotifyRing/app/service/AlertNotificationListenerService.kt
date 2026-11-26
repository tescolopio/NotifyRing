package com.NotifyRing.app.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.NotifyRing.app.telecom.AlertPayload
import com.NotifyRing.app.telecom.LocalCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlertNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val batches = mutableMapOf<Pair<String, String>, Batch>()
    private val lastProcessedContent = mutableMapOf<String, NotificationContent>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val selected = getSharedPreferences("monitored_apps", MODE_PRIVATE)
            .getStringSet("packages", emptySet()).orEmpty()
        if (sbn.packageName == packageName || sbn.packageName !in selected) return

        val notification = sbn.notification
        if (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT)
        ).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull().orEmpty()
        if (message.isEmpty()) return

        val cleanMessage = if (message.equals("Sensitive notification content hidden", ignoreCase = true)) {
            "Notification content is hidden. Open the app to view it."
        } else {
            message
        }
        val content = NotificationContent(sender, cleanMessage)
        if (lastProcessedContent[sbn.key] == content) return
        lastProcessedContent[sbn.key] = content

        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val key = sbn.packageName to sender
        val existing = batches[key]
        if (existing != null) {
            existing.messages.add(cleanMessage)
            return
        }
        val messages = mutableListOf(cleanMessage)
        val job = scope.launch {
            delay(10_000)
            batches.remove(key)
            LocalCallManager.startIncomingCall(
                applicationContext,
                AlertPayload(appName, sender, messages.joinToString(". "))
            )
        }
        batches[key] = Batch(messages, job)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastProcessedContent.remove(sbn.key)
    }

    override fun onDestroy() {
        batches.values.forEach { it.job.cancel() }
        batches.clear()
        lastProcessedContent.clear()
        scope.cancel()
        super.onDestroy()
    }

    private data class NotificationContent(val sender: String, val message: String)
    private data class Batch(val messages: MutableList<String>, val job: Job)
}
