package com.notifyring.app.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallException
import androidx.core.telecom.CallsManager
import com.notifyring.app.ui.call.IncomingCallActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Active per-call control handle registered by [TelecomCallDriver].
 */
interface TelecomCallHandle {
    suspend fun answer(): CallOpResult
    suspend fun disconnect(reason: CallDisconnectReason): CallOpResult
    suspend fun setActive(): CallOpResult
    suspend fun setInactive(): CallOpResult
    suspend fun requestEndpointChange(endpointId: String): CallOpResult
}

/**
 * Callbacks emitted from the underlying Telecom (or fallback) session into [LocalCallManager].
 */
interface TelecomCallCallbacks {
    suspend fun onCallRegistered(handle: TelecomCallHandle)
    suspend fun onRemoteAnswer()
    suspend fun onRemoteDisconnect(reason: CallDisconnectReason)
    suspend fun onSetActive()
    suspend fun onSetInactive()
    fun onCurrentEndpointChanged(endpoint: CallAudioEndpoint?)
    fun onAvailableEndpointsChanged(endpoints: List<CallAudioEndpoint>)
    fun onMutedChanged(isMuted: Boolean)
}

/**
 * Abstraction over `androidx.core:core-telecom` so [LocalCallManager] state-machine logic
 * is 100% testable in pure JVM unit tests without Robolectric.
 */
interface TelecomCallDriver {
    /**
     * Registers the app's phone account capabilities with the system Telecom stack.
     * Throws [UnsupportedOperationException] or [SecurityException] if Telecom is unavailable.
     */
    fun registerApp()

    /**
     * Runs an incoming call session until disconnected or cancelled.
     */
    suspend fun runIncomingCall(
        callId: UUID,
        payload: AlertPayload,
        callbacks: TelecomCallCallbacks
    )
}

/**
 * Bridge for foreground-service and fallback UI side effects so [LocalCallManager]
 * does not invoke Android framework `Intent`/`ContextCompat` APIs directly in JVM tests.
 */
interface CallForegroundUiBridge {
    fun startRingingForegroundService(
        callId: UUID,
        payload: AlertPayload,
        launchFallbackActivity: Boolean
    )

    fun showConnectedNotification(callId: UUID, payload: AlertPayload)

    fun stopForegroundService(callId: UUID)
}

/**
 * Minimal non-PII diagnostic logger abstraction so JVM tests avoid unmocked `android.util.Log`.
 */
fun interface CallDiagnosticLogger {
    fun warn(code: String)
}

internal object AndroidCallDiagnosticLogger : CallDiagnosticLogger {
    private const val TAG = "NotifyRingTelecom"

    override fun warn(code: String) {
        Log.w(TAG, code)
    }
}

/**
 * Production `androidx.core:core-telecom:1.1.0` driver implementation.
 */
class AndroidCoreTelecomDriver(
    context: Context
) : TelecomCallDriver {
    private val appContext: Context = context.applicationContext
    private val callsManager: CallsManager by lazy { CallsManager(appContext) }

    override fun registerApp() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM)) {
            throw UnsupportedOperationException("Device does not declare FEATURE_TELECOM")
        }
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }

    override suspend fun runIncomingCall(
        callId: UUID,
        payload: AlertPayload,
        callbacks: TelecomCallCallbacks
    ) {
        val attributes = CallAttributesCompat(
            displayName = buildDisplayName(payload),
            address = buildCallAddress(payload.packageName),
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE
        )
        val endpointLookup = ConcurrentHashMap<String, CallEndpointCompat>()

        callsManager.addCall(
            attributes,
            onAnswer = { _ ->
                callbacks.onRemoteAnswer()
            },
            onDisconnect = { cause ->
                callbacks.onRemoteDisconnect(cause.toDomainReason())
            },
            onSetActive = {
                callbacks.onSetActive()
            },
            onSetInactive = {
                callbacks.onSetInactive()
            }
        ) {
            val controlScope: CallControlScope = this
            val handle = CoreTelecomCallHandle(
                appContext = appContext,
                scope = controlScope,
                endpointLookup = endpointLookup
            )

            launch {
                currentCallEndpoint.collect { compat ->
                    val mapped = compat.toDomainEndpoint()
                    endpointLookup[mapped.id] = compat
                    callbacks.onCurrentEndpointChanged(mapped)
                }
            }
            launch {
                availableEndpoints.collect { compatList ->
                    val mappedList = compatList.map { compat ->
                        val mapped = compat.toDomainEndpoint()
                        endpointLookup[mapped.id] = compat
                        mapped
                    }
                    callbacks.onAvailableEndpointsChanged(mappedList)
                }
            }
            launch {
                isMuted.collect { muted ->
                    callbacks.onMutedChanged(muted)
                }
            }

            // Start undispatched so active handle & CallForegroundService registration run
            // immediately after CallsManager.addCall enters CallControlScope (API 34/35 requirement).
            launch(start = CoroutineStart.UNDISPATCHED) {
                callbacks.onCallRegistered(handle)
            }
        }
    }

    private fun buildDisplayName(payload: AlertPayload): String {
        val app = payload.appName.trim()
        val caller = payload.sender.trim()
        return when {
            app.isNotEmpty() && caller.isNotEmpty() -> "$app: $caller"
            caller.isNotEmpty() -> caller
            app.isNotEmpty() -> app
            else -> "NotifyRing"
        }
    }

    private fun buildCallAddress(packageName: String): Uri {
        val sanitizedPkg = sanitizePackageForSip(packageName)
        if (sanitizedPkg.isNotEmpty()) {
            val sipUri = runCatching {
                Uri.fromParts("sip", "$sanitizedPkg@notifyring.local", null)
            }.getOrNull()
            if (sipUri != null) return sipUri
        }
        return Uri.fromParts("tel", "0000", null)
    }
}

private class CoreTelecomCallHandle(
    private val appContext: Context,
    private val scope: CallControlScope,
    private val endpointLookup: ConcurrentHashMap<String, CallEndpointCompat>
) : TelecomCallHandle {

    override suspend fun answer(): CallOpResult = executeControlOp {
        scope.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
    }

    override suspend fun disconnect(reason: CallDisconnectReason): CallOpResult = executeControlOp {
        scope.disconnect(reason.toTelecomDisconnectCause())
    }

    override suspend fun setActive(): CallOpResult = executeControlOp {
        scope.setActive()
    }

    override suspend fun setInactive(): CallOpResult = executeControlOp {
        scope.setInactive()
    }

    override suspend fun requestEndpointChange(endpointId: String): CallOpResult {
        val target = endpointLookup[endpointId]
            ?: return CallOpResult.Error(CallException.ERROR_UNKNOWN, "Endpoint not found")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            target.type == CallEndpointCompat.TYPE_BLUETOOTH &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return CallOpResult.Error(
                CallException.ERROR_BLUETOOTH_DEVICE_IS_NULL,
                "BLUETOOTH_CONNECT not granted"
            )
        }

        return executeControlOp {
            scope.requestEndpointChange(target)
        }
    }

    private suspend fun executeControlOp(
        block: suspend () -> CallControlResult
    ): CallOpResult {
        return try {
            when (val result = block()) {
                is CallControlResult.Success -> CallOpResult.Success
                is CallControlResult.Error -> CallOpResult.Error(result.errorCode)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            CallOpResult.Error(CallException.ERROR_UNKNOWN, se.javaClass.simpleName)
        } catch (e: RuntimeException) {
            CallOpResult.Error(CallException.ERROR_UNKNOWN, e.javaClass.simpleName)
        }
    }
}

/**
 * Production Android implementation starting/updating/stopping [CallForegroundService]
 * and launching [IncomingCallActivity] when falling back without `FEATURE_TELECOM`.
 */
class AndroidCallForegroundUiBridge(
    context: Context
) : CallForegroundUiBridge {
    private val appContext: Context = context.applicationContext

    override fun startRingingForegroundService(
        callId: UUID,
        payload: AlertPayload,
        launchFallbackActivity: Boolean
    ) {
        val serviceIntent = Intent(appContext, CallForegroundService::class.java)
            .setAction(CallForegroundService.ACTION_START_RINGING)
            .putExtra(CallForegroundService.EXTRA_CALL_ID, callId.toString())
            .putExtra(CallForegroundService.EXTRA_APP_NAME, payload.appName)

        ContextCompat.startForegroundService(appContext, serviceIntent)

        if (launchFallbackActivity) {
            runCatching {
                val activityIntent = Intent(appContext, IncomingCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(CallForegroundService.EXTRA_CALL_ID, callId.toString())
                    .putExtra(CallForegroundService.EXTRA_AUTO_ANSWER, false)
                appContext.startActivity(activityIntent)
            }
        }
    }

    override fun showConnectedNotification(callId: UUID, payload: AlertPayload) {
        CallForegroundService.showConnected(appContext, callId, payload)
    }

    override fun stopForegroundService(callId: UUID) {
        CallForegroundService.stopForCall(appContext, callId)
    }
}

private fun CallEndpointCompat.toDomainEndpoint(): CallAudioEndpoint {
    val mappedType = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioEndpointType.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> AudioEndpointType.SPEAKER
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioEndpointType.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioEndpointType.WIRED_HEADSET
        CallEndpointCompat.TYPE_STREAMING -> AudioEndpointType.STREAMING
        else -> AudioEndpointType.UNKNOWN
    }
    return CallAudioEndpoint(
        id = identifier.toString(),
        name = name.toString(),
        type = mappedType
    )
}

private fun CallDisconnectReason.toTelecomDisconnectCause(): DisconnectCause {
    val code = when (this) {
        CallDisconnectReason.LOCAL -> DisconnectCause.LOCAL
        CallDisconnectReason.REJECTED -> DisconnectCause.REJECTED
        CallDisconnectReason.MISSED -> DisconnectCause.MISSED
        CallDisconnectReason.REMOTE -> DisconnectCause.REMOTE
        CallDisconnectReason.ERROR -> DisconnectCause.ERROR
    }
    return DisconnectCause(code)
}

private fun DisconnectCause.toDomainReason(): CallDisconnectReason {
    return when (code) {
        DisconnectCause.LOCAL -> CallDisconnectReason.LOCAL
        DisconnectCause.REJECTED -> CallDisconnectReason.REJECTED
        DisconnectCause.MISSED -> CallDisconnectReason.MISSED
        DisconnectCause.ERROR -> CallDisconnectReason.ERROR
        else -> CallDisconnectReason.REMOTE
    }
}
