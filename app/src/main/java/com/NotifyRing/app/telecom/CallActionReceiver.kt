package com.notifyring.app.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Non-exported [BroadcastReceiver] handling notification action buttons for declining an incoming
 * alert call (`REJECTED`) or hanging up an active alert call (`LOCAL`).
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val rawCallId = intent.getStringExtra(CallForegroundService.EXTRA_CALL_ID) ?: return
        val callId = try {
            UUID.fromString(rawCallId)
        } catch (_: IllegalArgumentException) {
            return
        }

        val reason = when (action) {
            ACTION_DECLINE -> CallDisconnectReason.REJECTED
            ACTION_END -> CallDisconnectReason.LOCAL
            else -> return
        }

        val pendingResult = goAsync()
        val manager = LocalCallManager.getInstance(context.applicationContext)
        manager.launchExternalAction {
            try {
                manager.disconnect(callId, reason)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.notifyring.app.ACTION_DECLINE"
        const val ACTION_END = "com.notifyring.app.ACTION_END"
    }
}
