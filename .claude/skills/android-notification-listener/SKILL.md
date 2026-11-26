---
name: android-notification-listener
description: Implements and reviews Android NotificationListenerService ingestion, MessagingStyle and InboxStyle parsing, Android 15 OTP and sensitive redaction handling, self-loop guards, and Doze or rebind resilience on API 29-35. Use when building, debugging, or auditing notification listeners, status bar notification filters, or background notification-to-action bridges.
metadata:
  skillforge-domain: android
  skillforge-version: "0.1.0"
---

# Android NotificationListenerService (API 29-35)

Follow this procedure when implementing or auditing a `NotificationListenerService` that filters incoming notifications and triggers local actions (such as ringing or reading alerts aloud).

## 1. Service declaration and binding

1. Declare the service in `AndroidManifest.xml` with:
   - `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`
   - `android:exported="true"` (required so the system NotificationManager can bind to it)
   - `<intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>`
2. Check listener grant status via `NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)` (or `Settings.Secure.getString(resolver, "enabled_notification_listeners")`). Direct users to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` when not granted.
3. Override `onListenerConnected()` and `onListenerDisconnected()`:
   - In `onListenerDisconnected()`, request rebinding via `requestRebind(ComponentName(this, YourListenerService::class.java))` (guarding against tight retry loops).

## 2. Mandatory guardrails in onNotificationPosted

Always override the two-argument `onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap)` (do not rely solely on the legacy single-argument overload). Apply these filters **in order** before any heavy work:

1. **Self-package and self-loop guard**:
   - Immediately return if `sbn.packageName == packageName`. Never react to your own foreground service or incoming-call notification.
2. **Ongoing and group summary guard**:
   - Ignore `Notification.FLAG_GROUP_SUMMARY` (otherwise group containers trigger duplicate events alongside child notifications).
   - Ignore `sbn.isOngoing` / `Notification.FLAG_ONGOING_EVENT` and `FLAG_FOREGROUND_SERVICE` unless explicitly opted in.
3. **Call category guard**:
   - Ignore `notification.category == Notification.CATEGORY_CALL` (and `Notification.CATEGORY_VOIP` / system dialer packages) so you never ring over an active cellular or VoIP call.
4. **Monitored app and rule allowlist**:
   - Check the user's configured allowlist of packages/rules from in-memory cached state (updated reactively from DataStore); never perform blocking disk I/O inside `onNotificationPosted`.
5. **Deduplication**:
   - Deduplicate rapid re-posts using a bounded TTL cache keyed on `(sbn.key, notification.when, contentHash)` so progress updates or re-posted threads do not re-trigger within the cooldown window.

## 3. Extracting notification content safely

Parse notification text in priority order rather than reading only `EXTRA_TEXT`:

1. **MessagingStyle** (`NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)`):
   - Take the latest `Message` (`messages.lastOrNull()`), reading `person?.name` as sender and `text` as body.
   - For incremental messaging updates, compare the latest message timestamp/text against the last seen message for `sbn.key` so you only trigger on genuinely new messages.
2. **InboxStyle** (`Notification.EXTRA_TEXT_LINES`):
   - Take the last non-blank line (or join the newest lines cleanly) alongside `EXTRA_TITLE`.
3. **BigTextStyle / Standard**:
   - Prefer `Notification.EXTRA_BIG_TEXT` over `Notification.EXTRA_TEXT`, with `Notification.EXTRA_TITLE` (or `EXTRA_CONVERSATION_TITLE`) as sender/header.
4. **Sanitization**:
   - Trim whitespace, collapse newlines, and cap extracted strings to a safe length (for example, 500 characters) before passing to UI or TTS.
   - Never log notification body text (`EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `MessagingStyle` messages) in release builds.

## 4. Android 15 (API 35) OTP and sensitive-notification redaction

On Android 15+, the OS automatically redacts notifications containing one-time passwords (OTPs) or sensitive content for untrusted `NotificationListenerService` apps:

1. **Do NOT request `android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS`**: it is a restricted permission reserved for companion device / system roles and causes Play Console rejection for normal apps.
2. **Detect redaction without fragile English-only string matching**:
   - On API 35+, redacted notifications often arrive with empty/null body extras, stripped `MessagingStyle` messages, or generic system placeholder text that varies by locale and OEM.
   - Check `rankingMap.getRanking(sbn.key, ranking)` and inspect whether `notification.publicVersion` or empty/placeholder content replaced the real payload.
   - Do not hardcode English-only checks like `text == "Sensitive notification content hidden"` as your sole detection path.
3. **Graceful fallback**:
   - When content is redacted or empty, still notify the user cleanly using the app label (and sender/title if present), e.g., announcing "New notification from \<App Name\>" without failing or crashing.

## 5. Doze, WakeLock, and background resilience

1. Keep `onNotificationPosted` fast and non-blocking: hand off accepted events to a service-scoped coroutine (`SupervisorJob()`) or start your `phoneCall` foreground service immediately.
2. If acquiring a `PowerManager.PARTIAL_WAKE_LOCK` to bridge Doze between `onNotificationPosted` and foreground service startup:
   - **Always pass an explicit timeout** (`wakeLock.acquire(10_000L)`), never an unbounded `acquire()`.
   - Release it in a `finally` block once the foreground service has started.
3. Do **not** declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` just to keep the listener alive; Play Store rejects non-exempt apps. Instead, guide users to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` or OEM-specific battery settings during onboarding.

## 6. Verification before finishing

1. Run `./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug`.
2. Verify unit tests cover:
   - Self-package, `FLAG_GROUP_SUMMARY`, `isOngoing`, and `CATEGORY_CALL` rejection.
   - `MessagingStyle`, `InboxStyle`, `EXTRA_BIG_TEXT`, and standard `EXTRA_TEXT` extraction.
   - Deduplication of identical re-posted `sbn.key` events and incremental `MessagingStyle` updates.
   - Android 15 redacted/empty content fallback.
3. Verify every `WakeLock.acquire` call in the codebase specifies a finite timeout.
