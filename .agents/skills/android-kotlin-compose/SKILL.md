---
name: android-kotlin-compose
description: Guides Android Kotlin DSL, Jetpack Compose, Material 3, Lifecycle, and Coroutines implementation for API 29-35. Use when authoring or reviewing Android Gradle Kotlin DSL builds, Compose UI screens, ViewModels, state management, permission flows, or coroutine concurrency in an Android app.
metadata:
  skillforge-domain: android
  skillforge-version: "0.1.0"
---

# Android Kotlin and Jetpack Compose (API 29-35)

Follow these defaults when building or modifying Android apps targeting API 29 through API 35 with Kotlin, Gradle Kotlin DSL, and Jetpack Compose.

## 1. Gradle Kotlin DSL and version catalog

1. Keep dependency versions centralized in `gradle/libs.versions.toml` using `[versions]`, `[libraries]`, `[bundles]`, and `[plugins]`. Avoid hardcoded version strings in `app/build.gradle.kts`.
2. Ensure `settings.gradle.kts` declares `google()` and `mavenCentral()` in both `pluginManagement.repositories` and `dependencyResolutionManagement.repositories` (with `gradlePluginPortal()` in `pluginManagement`).
3. Pin `compileSdk = 35`, `targetSdk = 35`, and `minSdk = 29` (or the project's documented minimum). Enable `compose = true` via the Kotlin Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) on Kotlin 2.0+.
4. Align Compose library versions with a single `androidx.compose:compose-bom` platform dependency; do not mix unaligned Compose artifact versions.
5. Keep application IDs and package names lowercase (`com.example.app`) and match `namespace` in `app/build.gradle.kts` to the code package.

## 2. Jetpack Compose and Material 3

1. **Unidirectional data flow**: pass immutable `UiState` down and event lambdas up. Never pass a `ViewModel`, `Context`, or mutable `MutableStateFlow` deep into leaf composables.
2. **Lifecycle-aware collection**: collect `StateFlow` in Compose with `collectAsStateWithLifecycle()` (from `androidx.lifecycle:lifecycle-runtime-compose`), not `collectAsState()`.
3. **Side effects**:
   - Use `LaunchedEffect(key)` for suspending work tied to composition keys.
   - Use `DisposableEffect(key)` for registering/unregistering listeners or receivers, always cleaning up in `onDispose`.
   - Use `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` (or `LifecycleResumeEffect`) to re-check runtime permissions, notification listener access, or full-screen intent grants when the user returns from system Settings.
4. **Recomposition discipline**:
   - Wrap derived computations in `remember(keys)` or `derivedStateOf` when reading rapidly changing state (scroll position, timers).
   - Provide stable `key` lambdas in `LazyColumn` / `LazyRow` items.
5. **Material 3 and edge-to-edge**:
   - Call `enableEdgeToEdge()` in `ComponentActivity.onCreate` before `setContent` and apply `Scaffold` `innerPadding` or `WindowInsets.safeDrawing` so content does not collide with API 35's enforced edge-to-edge system bars.
   - Use `MaterialTheme.colorScheme` and `MaterialTheme.typography` tokens instead of hardcoded colors or font sizes. Support dynamic color on API 31+ with a deterministic static fallback on API 29-30.

## 3. Coroutines and concurrency

1. **Structured concurrency**: launch UI-scoped work in `viewModelScope` or `lifecycleScope`, and service-scoped work in a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` that is explicitly canceled in `Service.onDestroy()`. Never use `GlobalScope`.
2. **Dispatcher injection**: inject `CoroutineDispatcher`s (defaulting to `Dispatchers.IO` / `Dispatchers.Default`) into repositories, managers, and services so unit tests can substitute `StandardTestDispatcher` / `UnconfinedTestDispatcher`.
3. **Shared mutable state**: guard multi-step state transitions (such as call lifecycle or audio routing state) with a Kotlin `Mutex` (`mutex.withLock { ... }`) or a single-threaded actor/channel; never rely on unsynchronized `var` fields read across Binder/Telecom/UI threads.
4. **Cancellation safety**: never swallow `CancellationException` in broad `catch (e: Exception)` blocks—rethrow `CancellationException` before logging or mapping errors.

## 4. API 29-35 compatibility guardrails

1. Guard every API-gated call with explicit `Build.VERSION.SDK_INT >= Build.VERSION_CODES.<LEVEL>` checks:
   - API 31 (`S`): `PendingIntent.FLAG_IMMUTABLE` (or `FLAG_MUTABLE` only when the system must fill extras), `BLUETOOTH_CONNECT` runtime permission, dynamic Material You colors.
   - API 33 (`TIRAMISU`): `POST_NOTIFICATIONS` runtime permission, `RECEIVER_NOT_EXPORTED` / `RECEIVER_EXPORTED` on `registerReceiver`, typed `getParcelableExtra(name, clazz)`.
   - API 34 (`UPSIDE_DOWN_CAKE`): explicit foreground service type parameter in `ServiceCompat.startForeground(...)`, `NotificationManager.canUseFullScreenIntent()`.
   - API 35 (`VANILLA_ICE_CREAM`): edge-to-edge enforcement, sensitive notification redaction, stricter FGS start restrictions from `BOOT_COMPLETED`.
2. Persist user preferences with Jetpack DataStore (`preferencesDataStore` or Proto DataStore) rather than blocking `SharedPreferences.commit()` on the main thread.

## 5. Verification before finishing

Run the project's Gradle verification suite after every code or build change:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
```

Confirm:
- Zero compiler warnings introduced in touched files.
- Unit tests cover state transitions, permission re-check logic, and edge cases using `kotlinx-coroutines-test`.
- `lintDebug` reports zero new errors (especially `NewApi`, `MissingPermission`, `InlinedApi`, and `UnspecifiedRegisterReceiverFlag`).
