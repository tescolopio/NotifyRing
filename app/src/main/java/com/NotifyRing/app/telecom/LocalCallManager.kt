package com.NotifyRing.app.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque

data class AlertPayload(val appName: String, val sender: String, val message: String)

object LocalCallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingCalls = ArrayDeque<Pair<Context, AlertPayload>>()
    private var busy = false
    private var call: CallControlScope? = null

    fun startIncomingCall(context: Context, payload: AlertPayload) {
        scope.launch {
            pendingCalls.addLast(context.applicationContext to payload)
            startNextCall()
        }
    }

    private fun startNextCall() {
        if (busy) return
        val (context, payload) = pendingCalls.pollFirst() ?: return
        busy = true
        scope.launch {
            try {
                val manager = CallsManager(context)
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                val attributes = CallAttributesCompat(
                    displayName = payload.appName,
                    address = Uri.parse("notifyring:alert"),
                    direction = CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL
                )
                manager.addCall(
                    attributes,
                    onAnswer = { CallForegroundService.showConnected(context, payload.appName) },
                    onDisconnect = { },
                    onSetActive = { },
                    onSetInactive = { }
                ) {
                    call = this
                    try {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, CallForegroundService::class.java)
                                .putExtra(CallForegroundService.EXTRA_APP_NAME, payload.appName)
                        )
                    } catch (e: RuntimeException) {
                        Log.w("NotifyRing", "Unable to start the call service", e)
                        scope.launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                    }
                }
            } catch (e: Exception) {
                Log.w("NotifyRing", "Unable to start an alert call", e)
            } finally {
                call = null
                context.stopService(Intent(context, CallForegroundService::class.java))
                busy = false
                startNextCall()
            }
        }
    }

    fun answer(context: Context, appName: String) {
        scope.launch {
            if (call?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) is CallControlResult.Success) {
                CallForegroundService.showConnected(context, appName)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            call?.disconnect(DisconnectCause(DisconnectCause.LOCAL))
        }
    }
}
