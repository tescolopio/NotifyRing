# NotifyRing

Many older adults miss critical text messages, pharmacy updates, doctor portal alerts, or family chats because passive notification banners are easy to overlook, hard to read, or gone before they reach their phone. **NotifyRing** bridges that gap by transforming selected app notifications into familiar, full-screen incoming phone calls that ring aloud and speak the alert clearly using on-device Text-to-Speech.

- **Targets:** Android 10–15 (`minSdk = 29`, `targetSdk = 35`)
- **Privacy:** 100% offline, zero `INTERNET` permission, zero analytics/telemetry SDKs, zero cloud backup or device-to-device data extraction

---

## Architecture Overview

| Layer | Key Components | Responsibilities |
| :--- | :--- | :--- |
| **Data & Persistence** | `DataStoreSettingsRepository`, `UserSettings`, `PinHasher`, `AlertCallLogStore` | Persists monitored app packages, debounce window, ring timeout, auto-speaker, speech rate, onboarding completion, and salted SHA-256 caregiver PIN hash in Jetpack DataStore; maintains a bounded on-device JSON alert call history. |
| **Core-Telecom Call Engine** | `LocalCallManager`, `CallForegroundService`, `CallRingtoneController`, `CallActionReceiver` | Single-flight `androidx.core:core-telecom` call lifecycle (`Ringing` → `Active` → `Ended`), `foregroundServiceType="phoneCall"` with high-priority `CallStyle` notification + full-screen intent, ringtone/vibration playback, and notification action handling (Answer / Decline / End). |
| **Speech Engine** | `CallSpeechEngine`, `SpeechScriptFormatter`, `TtsEngineAdapter` | On-device TTS routed over `AudioAttributes.USAGE_VOICE_COMMUNICATION` with lead-in delay, `requestEndpointChange` speaker/Bluetooth/earpiece coordination, pause/mute/replay controls, and clean teardown on disconnect. |
| **Incoming Call UI** | `IncomingCallActivity`, `IncomingCallScreen`, `IncomingCallUiState` | Lock-screen capable (`showWhenLocked`, `turnScreenOn`, `singleTop`) senior-friendly call screen with oversized Answer/Decline/End/Speaker/Replay/Open App controls, Back interception during ringing/active calls, and auto-answer guard. |
| **Notification Bridge (API 35)** | `AlertNotificationListenerService` | `NotificationListenerService` that filters by user-selected package allowlist, ignores self/ongoing/summary/low-priority noise, debounces bursts per package/conversation, and handles Android 15 sensitive-content / OTP redaction safely. |
| **Compose App UI** | `OnboardingWizardScreen`, `SeniorHomeScreen`, `CaregiverSettingsScreen`, `CaregiverPinGate` | 8-step guided onboarding wizard (with Prominent Disclosure & API 29–35 permission checks), Senior Home dashboard (protection status, degraded-permission banners, Test Call, recent alerts with one-tap **Call & Replay**), and 4-digit PIN-gated Caregiver Settings. |

---

## Zero-Internet Privacy & CI Gate

NotifyRing enforces a strict offline-first privacy boundary at build and CI time:

- `app/src/main/AndroidManifest.xml` strips `android.permission.INTERNET` defensively via `tools:node="remove"`, uses scoped `<queries>` (`ACTION_MAIN` / `CATEGORY_LAUNCHER`) instead of `QUERY_ALL_PACKAGES`, and omits `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- `android:allowBackup="false"`, `android:fullBackupContent="@xml/backup_rules"`, and `android:dataExtractionRules="@xml/data_extraction_rules"` block both cloud backups and Android 12+ Device-to-Device (D2D) transfers.
- `app/build.gradle.kts` defines the **`verifyMergedManifestPrivacy`** Gradle task (automatically wired into `check`), which XML-parses merged manifests (`debug` and, when assembled, `release`) and fails the build if any `<uses-permission>` or `<uses-permission-sdk-23>` declares:
  - `android.permission.INTERNET`
  - `android.permission.QUERY_ALL_PACKAGES`
  - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### Build, Test, Lint & Privacy Verification

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug verifyMergedManifestPrivacy
```

---

## ADB Testing Commands

### 1. Grant & Verify Notification Listener Access

```bash
# Grant Notification Listener access to NotifyRing
adb shell cmd notification allow_listener com.notifyring.app/com.notifyring.app.service.AlertNotificationListenerService

# Confirm enabled listeners
adb shell settings get secure enabled_notification_listeners
```

> **Android 13+ (API 33+) sideloaded APK note:** If Notification Access is grayed out with *"Restricted setting"*, open **Settings → Apps → NotifyRing → ⋮ (top-right) → Allow restricted settings**, then re-enable Notification Access.

### 2. Inspect Notification & Full-Screen Intent Permissions (API 33–35)

```bash
# Grant POST_NOTIFICATIONS on Android 13+
adb shell pm grant com.notifyring.app android.permission.POST_NOTIFICATIONS

# Open Full-Screen Intent permission screen on Android 14+ (API 34+)
adb shell am start -a android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT -d package:com.notifyring.app

# Inspect appops / notification channel state
adb shell dumpsys notification --noredact | grep -A 15 "com.notifyring.app"
```

### 3. Simulate an Incoming Monitored Notification

```bash
# Post a test notification from the Android shell (add "com.android.shell" to monitored apps for testing,
# or post from a selected test app package)
adb shell cmd notification post \
  -S bigtext \
  -t "Dr. Patel (MyChart)" \
  "notifyring_test_tag" \
  "Your prescription is ready for pickup at Main Street Pharmacy."
```

### 4. Test Android 15 (API 35) Sensitive-Content / OTP Redaction

On Android 15, the OS automatically redacts notifications containing one-time passcodes (OTPs) or sensitive content from untrusted notification listeners (replacing text with a generic placeholder such as *"Sensitive notification content hidden"*):

```bash
# Simulate an OTP-like notification on Android 15 to verify NotifyRing degrades gracefully
# without crashing or speaking raw redacted system placeholders awkwardly
adb shell cmd notification post \
  -t "Bank Alert" \
  "otp_test_tag" \
  "Your verification code is 482910. Do not share this code."

# Watch NotifyRing logs (confirm no raw PII / message body is logged in release)
adb logcat -s NotifyRing:* AlertNotificationListener:* LocalCallManager:*
```

---

## Google Play Review Readiness

Before submitting to Google Play Console, prepare the following declarations and review materials:

1. **`FOREGROUND_SERVICE_PHONE_CALL` (App Content Declaration + Demo Video)**
   - Declare `phoneCall` foreground service usage under **App Content → Foreground service permissions**.
   - Provide a short screen-recorded demo video showing a user configuring a monitored app, receiving a notification, seeing the full-screen incoming alert call ring, answering it, and hearing the alert read aloud before hanging up.
   - `CallForegroundService` runs exclusively during an active ringing/connected alert call and stops immediately upon disconnect.
2. **`USE_FULL_SCREEN_INTENT` (Calling / Alarm Declaration & API 34+ Fallback)**
   - Complete the Play Console declaration justifying full-screen incoming call alerts.
   - On Android 14+ (API 34+), `NotificationManager.canUseFullScreenIntent()` is checked at runtime; if denied or revoked, NotifyRing surfaces a degraded-permission banner linking directly to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` and falls back gracefully to a high-priority heads-up notification.
3. **Prominent Disclosure for Notification Listener**
   - Step 1 of `OnboardingWizardScreen` presents an explicit in-app Prominent Disclosure explaining why Notification Access is requested, confirming all processing stays strictly on-device with zero network transmission, and requiring affirmative acceptance before opening system Notification Access settings.
4. **Data Safety (Zero Data Collection)**
   - Declare **"No user data collected or shared"** in Play Console Data Safety.
   - Backed by the automated `verifyMergedManifestPrivacy` CI gate, zero `INTERNET` permission, zero third-party analytics/crash/ad SDKs, and disabled cloud/D2D backups.
5. **Why `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` Is Excluded**
   - Google Play rejects non-exempt apps that declare `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   - NotifyRing omits this permission entirely (enforced by `verifyMergedManifestPrivacy`) and instead guides caregivers to the standard system battery settings screen (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) during onboarding and in Caregiver Settings.
