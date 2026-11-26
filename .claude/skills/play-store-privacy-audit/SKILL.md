---
name: play-store-privacy-audit
description: Audits Android manifests, merged manifests, Gradle dependencies, backup rules, and runtime flows for Google Play Store policy and zero-data-collection privacy compliance. Use when checking zero INTERNET permission, zero QUERY_ALL_PACKAGES, USE_FULL_SCREEN_INTENT API 34+ compliance, FOREGROUND_SERVICE_TYPE_PHONE_CALL declarations, battery optimization policy, Android 12+ dataExtractionRules, or Play Data Safety disclosures.
metadata:
  skillforge-domain: security
  skillforge-version: "0.1.0"
---

# Google Play Store and Privacy Audit

Use this checklist to audit an offline-first or privacy-strict Android app before release or after any manifest, dependency, or permission change. Report only verified findings backed by file paths, line numbers, or merged-manifest evidence.

## 1. Zero INTERNET permission verification

1. Inspect `app/src/main/AndroidManifest.xml` (and any `debug`/`release` manifests) for `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.
2. Require a defensive removal guard in the main manifest so no transitive library can silently re-introduce network access:
   ```xml
   <uses-permission
       android:name="android.permission.INTERNET"
       tools:node="remove" />
   ```
3. Build the merged manifest (`./gradlew --no-daemon processDebugMainManifest` or `assembleDebug`) and inspect:
   - `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`
   - `app/build/outputs/logs/manifest-merger-debug-report.txt`
4. Confirm `android.permission.INTERNET` is completely absent from the merged manifest. Fail the audit if any dependency adds it without removal.

## 2. Zero QUERY_ALL_PACKAGES and package visibility

1. Verify `android.permission.QUERY_ALL_PACKAGES` is absent from both source and merged manifests.
2. When the app needs to list user-installed launchable apps (e.g., to pick monitored apps), verify it uses a scoped `<queries>` declaration instead:
   ```xml
   <queries>
       <intent>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.LAUNCHER" />
       </intent>
   </queries>
   ```
3. Confirm `PackageManager.queryIntentActivities(...)` queries that exact `MAIN`/`LAUNCHER` intent rather than enumerating all installed packages.

## 3. Sensitive Play Console permission policies (API 31-35)

Check each sensitive permission against Google Play policy and runtime requirements:

1. **`USE_FULL_SCREEN_INTENT` (API 34+)**:
   - Play restricts auto-grant to calling and alarm apps; other apps or users who revoke it in Settings receive `false` from `NotificationManager.canUseFullScreenIntent()`.
   - Verify the app checks `canUseFullScreenIntent()` on API 34+, provides an onboarding/settings path to `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, and falls back gracefully to a high-priority heads-up notification when denied.
2. **`FOREGROUND_SERVICE_TYPE_PHONE_CALL` / `FOREGROUND_SERVICE_PHONE_CALL`**:
   - Requires `android.permission.MANAGE_OWN_CALLS` (or default dialer role) and a Play Console App Content foreground-service declaration with a demo video showing core user-facing call functionality.
   - Verify every `<service>` with `android:foregroundServiceType="phoneCall"` starts only in response to an active user-configured call event and stops promptly when the call ends.
3. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**:
   - **High rejection risk**: Play rejects apps declaring this permission unless they fall into a narrow exempt category.
   - Flag any occurrence of `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as a **high-severity Play rejection finding**. Recommend removing the permission and linking users to `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` instead.
4. **Transitive permissions (e.g., `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`)**:
   - Audit `manifest-merger-*-report.txt` for every permission added by AARs (such as `androidx.core:core-telecom` merging `BLUETOOTH_CONNECT`).
   - Verify runtime permission checks (`ContextCompat.checkSelfPermission`) exist before any API 31+ Bluetooth call, or remove the permission with `tools:node="remove"` only if Bluetooth routing is intentionally unsupported.
5. **`RECEIVE_SENSITIVE_NOTIFICATIONS`**:
   - Flag immediately if present; restricted to system/companion roles.

## 4. Backup and Android 12+ data extraction rules

1. Check `android:allowBackup` and `android:dataExtractionRules` on `<application>`.
2. On API 31+, `android:allowBackup="false"` alone does **not** block Device-to-Device (D2D) transfers. Require `android:dataExtractionRules="@xml/data_extraction_rules"` with explicit `<cloud-backup>` and `<device-transfer>` exclusions for sensitive preferences and databases:
   ```xml
   <data-extraction-rules>
       <cloud-backup>
           <exclude domain="root" path="." />
           <exclude domain="file" path="." />
           <exclude domain="database" path="." />
           <exclude domain="sharedpref" path="." />
       </cloud-backup>
       <device-transfer>
           <exclude domain="root" path="." />
           <exclude domain="file" path="." />
           <exclude domain="database" path="." />
           <exclude domain="sharedpref" path="." />
       </device-transfer>
   </data-extraction-rules>
   ```
3. For API 29-30 compatibility, also set `android:allowBackup="false"` and `android:fullBackupContent="@xml/backup_rules"` (excluding all domains).

## 5. Data Safety zero-collection audit

1. Inspect `gradle/libs.versions.toml` and `app/build.gradle.kts` for analytics, crash-reporting, ad, or telemetry SDKs (Firebase Analytics, Crashlytics, Sentry, Mixpanel, etc.). Confirm none are present for a zero-collection app.
2. Scan Kotlin source files for PII/notification-content logging (`Log.d`, `Log.i`, `Log.e`, `println`) and verify release builds strip debug logs or never log notification payloads/caller text.
3. Verify exported components (`android:exported="true"`): only the launcher `Activity` and `BIND_NOTIFICATION_LISTENER_SERVICE` (protected by system permission) should be exported unless strictly justified.

## 6. Report format

Present findings grouped by severity (`[critical]`, `[high]`, `[medium]`, `[pass]`), citing exact file paths and merged-manifest evidence:

```text
[high] REQUEST_IGNORE_BATTERY_OPTIMIZATIONS declared
Location: app/src/main/AndroidManifest.xml:14
Evidence: Play Console rejects non-exempt apps declaring this permission.
Fix: Remove the permission and link users to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS.
```
