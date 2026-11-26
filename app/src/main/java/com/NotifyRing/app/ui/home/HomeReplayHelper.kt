package com.notifyring.app.ui.home

import com.notifyring.app.data.AlertCallLogEntry
import com.notifyring.app.telecom.AlertPayload
import com.notifyring.app.ui.onboarding.DegradedPermissionIssue
import com.notifyring.app.ui.onboarding.PermissionSnapshot

/**
 * Pure-Kotlin helper converting [AlertCallLogEntry] history rows into [AlertPayload]s for one-tap
 * "Call & Replay", constructing test-call payloads, and prioritizing degraded permission banners.
 */
object HomeReplayHelper {

    const val DEFAULT_APP_NAME = "NotifyRing"
    const val DEFAULT_TEST_SENDER = "Test Alert"
    const val DEFAULT_TEST_MESSAGE =
        "This is a NotifyRing test call. Your important notifications will ring and be read aloud just like this."
    const val DEFAULT_REPLAY_MESSAGE = "Replaying recent alert."

    fun buildReplayPayload(entry: AlertCallLogEntry): AlertPayload {
        val resolvedAppName = entry.appLabel.trim().ifEmpty { DEFAULT_APP_NAME }
        val resolvedSender = entry.sender.trim().ifEmpty { resolvedAppName }
        val resolvedMessage = entry.messagePreview.trim().ifEmpty { DEFAULT_REPLAY_MESSAGE }
        return AlertPayload(
            appName = resolvedAppName,
            packageName = entry.packageName.trim(),
            sender = resolvedSender,
            message = resolvedMessage
        )
    }

    fun buildTestCallPayload(
        packageName: String,
        appName: String = DEFAULT_APP_NAME,
        sender: String = DEFAULT_TEST_SENDER,
        message: String = DEFAULT_TEST_MESSAGE
    ): AlertPayload = AlertPayload(
        appName = appName.trim().ifEmpty { DEFAULT_APP_NAME },
        packageName = packageName.trim(),
        sender = sender.trim().ifEmpty { DEFAULT_TEST_SENDER },
        message = message.trim().ifEmpty { DEFAULT_TEST_MESSAGE }
    )

    fun resolvePrimaryDegradedIssue(snapshot: PermissionSnapshot): DegradedPermissionIssue? =
        snapshot.degradedIssues.firstOrNull()
}
