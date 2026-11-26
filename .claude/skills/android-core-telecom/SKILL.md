---
name: android-core-telecom
description: Implements and reviews Jetpack Core Telecom VoIP and local call flows using androidx.core:core-telecom, CallsManager, CallAttributesCompat, CallControlScope, audio routing, and phoneCall foreground services on API 29-35. Use when building, debugging, or auditing telecom call setup, audio endpoint switching, ring timeouts, disconnect causes, or MANAGE_OWN_CALLS and FOREGROUND_SERVICE_TYPE_PHONE_CALL integration.
metadata:
  skillforge-domain: android
  skillforge-version: "0.1.0"
---

# Android Jetpack Core Telecom

Use this procedure when implementing or reviewing local/VoIP call experiences built on `androidx.core:core-telecom` (API 29-35).

## 1. Manifest and permission setup

1. Declare the required permissions in `AndroidManifest.xml`:
   - `android.permission.MANAGE_OWN_CALLS`
   - `android.permission.FOREGROUND_SERVICE`
   - `android.permission.FOREGROUND_SERVICE_PHONE_CALL` (mandatory on API 34+)
   - `android.permission.POST_NOTIFICATIONS` (runtime on API 33+)
   - `android.permission.USE_FULL_SCREEN_INTENT` (if displaying a lockscreen incoming call UI; check `canUseFullScreenIntent()` on API 34+)
2. Declare the call foreground service with `android:foregroundServiceType="phoneCall"` and `android:exported="false"`.
3. **Account for transitive permissions merged by `core-telecom`**:
   - `androidx.core:core-telecom` merges `android.permission.BLUETOOTH_CONNECT` (API 31+ runtime permission) and `android.permission.MODIFY_AUDIO_SETTINGS`, plus `JetpackConnectionService` and `MuteStateReceiver`.
   - On API 31+, gate Bluetooth audio routing UI and endpoint switching gracefully when `BLUETOOTH_CONNECT` is not granted—never crash if the user denies Bluetooth access.

## 2. Registering with CallsManager

1. Instantiate `CallsManager(context)` once (singleton or DI-managed) and call `registerAppWithTelecom(capabilities)` before adding calls.
2. Choose the minimal capability set needed (default: `CallsManager.CAPABILITY_BASELINE`; add `CAPABILITY_SUPPORTS_CALL_STREAMING` only if actually implemented).
3. Keep `registerAppWithTelecom` idempotent and guarded against repeated initialization races.

## 3. Adding a call and managing CallControlScope

1. Build `CallAttributesCompat` with:
   - Non-empty `displayName` (caller/sender label)
   - Valid `address` (`Uri.fromParts("tel", ..., null)` or app-scoped `sip:`/`notifyring:` URI)
   - `CallAttributesCompat.DIRECTION_INCOMING` (or `DIRECTION_OUTGOING`)
   - `CallAttributesCompat.CALL_TYPE_AUDIO_CALL`
   - `CallAttributesCompat.SUPPORTS_SET_INACTIVE` if the call can be held when a cellular call arrives.
2. Call `callsManager.addCall(attributes, onAnswer, onDisconnect, onSetActive, onSetInactive) { ... }`:
   - `addCall` suspends until the call ends or fails. Launch it inside a service-scoped coroutine (`SupervisorJob()`), never in an Activity scope that dies on rotation.
   - Inside the `CallControlScope` block, observe `currentCallEndpoint`, `availableEndpoints`, and `isMuted` flows concurrently (e.g., child `launch` coroutines canceled when the call ends).
3. **Synchronize call state**:
   - Protect active call references, pending UI callbacks, and teardown flags with a Kotlin `Mutex` so simultaneous user tap, ring timeout, and Telecom callback (`onDisconnect`/`onSetInactive`) cannot double-disconnect or leak audio.
   - Retain all call metadata (`callId`, `sender`, `message`, `packageName`, `startedAt`) in a single immutable state model (`StateFlow<CallUiState>`) shared between the service, Telecom manager, and UI.

## 4. Audio routing and speech playback

1. Request audio endpoint changes through `CallControlScope.requestEndpointChange(endpoint)` and **always inspect the returned `CallControlResult`**:
   ```kotlin
   when (val result = requestEndpointChange(targetEndpoint)) {
       is CallControlResult.Success -> { /* update UI state */ }
       is CallControlResult.Error -> { /* log result.errorCode, keep current endpoint */ }
   }
   ```
2. Configure `TextToSpeech` or `AudioTrack` playback with voice-call audio attributes so Telecom routes audio to the active earpiece, speaker, or Bluetooth headset:
   ```kotlin
   val attrs = AudioAttributes.Builder()
       .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
       .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
       .build()
   ```
3. Do not manually fight Telecom with `AudioManager.setMode(MODE_IN_COMMUNICATION)` or legacy `startBluetoothSco()` when `core-telecom` is managing the call—let `CallControlScope` own audio focus and endpoint routing.
4. On `onSetInactive` (for example, when an incoming cellular call preempts the local call), pause or stop TTS/ringtone playback immediately and release playback resources until `onSetActive` or `onDisconnect`.

## 5. Ring timeout, foreground service, and disconnect causes

1. **Foreground service startup**:
   - Call `ServiceCompat.startForeground(this, notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)` within 5 seconds of `startForegroundService()`.
   - Build the incoming-call notification with `NotificationCompat.CallStyle.forIncomingCall(...)` (or high-priority `CATEGORY_CALL`) and distinct `PendingIntent` request codes + `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` for Answer, Decline, and full-screen actions.
2. **Ring timeout**:
   - Start a bounded timeout coroutine (default: 30 seconds) while the call is ringing. Cancel it immediately on `onAnswer` or user decline.
   - If the timeout expires before answer, disconnect with `DisconnectCause(DisconnectCause.MISSED)`.
3. **Explicit disconnect causes**:
   - User declines before answer: `DisconnectCause(DisconnectCause.REJECTED)`
   - User hangs up after answer: `DisconnectCause(DisconnectCause.LOCAL)`
   - Ring timeout expires: `DisconnectCause(DisconnectCause.MISSED)`
   - Preempted or canceled internally: `DisconnectCause(DisconnectCause.CANCELED)`
4. **Guaranteed teardown**: in a `finally` block after `addCall` exits, stop ringtone/TTS, emit `Idle` state, call `stopForeground(STOP_FOREGROUND_REMOVE)`, and `stopSelf()`.

## 6. Verification before finishing

1. Run `./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug`.
2. Inspect `app/build/outputs/logs/manifest-merger-debug-report.txt` to verify `JetpackConnectionService`, `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL`, and transitive `BLUETOOTH_CONNECT` are accounted for.
3. Verify unit tests cover: incoming -> answer -> local disconnect, incoming -> decline (`REJECTED`), incoming -> ring timeout (`MISSED`), `requestEndpointChange` error handling, and preemption via `onSetInactive`.
