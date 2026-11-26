package com.notifyring.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.notifyring.app.NotifyRingApplication
import com.notifyring.app.R
import com.notifyring.app.data.UserSettings
import com.notifyring.app.di.AppContainer
import com.notifyring.app.telecom.AlertPayload
import com.notifyring.app.telecom.LocalCallManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * API 29-35 hardened [NotificationListenerService] bridging monitored app notifications into
 * single-flight local Telecom alert calls without internet access or PII logging.
 */
class AlertNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val filterEngine = NotificationFilterEngine()
    private val deduplicator = MessagingStyleDeduplicator()

    @Volatile
    private var redactionDetector: RedactionDetector = RedactionDetector()

    private val _settingsCache = MutableStateFlow(UserSettings())
    internal val settingsCache: StateFlow<UserSettings> = _settingsCache.asStateFlow()
    private val settingsLoaded = AtomicBoolean(false)
    private var settingsCollectionJob: Job? = null

    private val batches = mutableMapOf<BatchKey, PendingBatch>()
    private var rebindAttempts: Int = 0

    private val appContainerOrNull: AppContainer?
        get() = (application as? NotifyRingApplication)
            ?.takeIf { it.isContainerInitialized }
            ?.container

    override fun onCreate() {
        super.onCreate()
        refreshRedactionDetector()
        startCollectingSettings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshRedactionDetector()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebindAttempts = 0
        deduplicator.clear()
        refreshRedactionDetector()
        if (settingsCollectionJob?.isActive != true) {
            startCollectingSettings()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cancelAllBatches()
        deduplicator.clear()
        if (rebindAttempts < MAX_REBIND_ATTEMPTS) {
            rebindAttempts += 1
            runCatching {
                requestRebind(ComponentName(this, AlertNotificationListenerService::class.java))
            }
        }
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap
    ) {
        val sourcePackage = sbn.packageName?.trim().orEmpty()
        val ownPackage = packageName?.trim().orEmpty()
        if (sourcePackage.isEmpty() || sourcePackage == ownPackage) {
            return
        }

        val sbnKey = sbn.key?.trim().orEmpty()
        if (sbnKey.isEmpty()) {
            return
        }

        val notification = sbn.notification ?: return
        val flags = notification.flags
        val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isForegroundService = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isClearable = sbn.isClearable

        val ranking = Ranking()
        val hasRanking = rankingMap.getRanking(sbnKey, ranking)
        val importance = if (hasRanking) {
            ranking.importance
        } else {
            NotificationFilterEngine.IMPORTANCE_DEFAULT
        }
        val isAmbient = hasRanking && ranking.isAmbient
        val matchesInterruptionFilter = if (hasRanking) {
            ranking.matchesInterruptionFilter()
        } else {
            true
        }
        val channel = if (hasRanking) ranking.channel else null
        val isChannelMuted = channel != null && channel.importance < NotificationManager.IMPORTANCE_DEFAULT
        val isMutedOrSuppressed = hasRanking && (ranking.isSuspended || isChannelMuted)
        val effectiveVisibility = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasRanking &&
            ranking.lockscreenVisibilityOverride == Notification.VISIBILITY_SECRET
        ) {
            RedactionDetector.VISIBILITY_SECRET
        } else {
            notification.visibility
        }

        if (settingsLoaded.get()) {
            processWithSettings(
                sbnKey = sbnKey,
                sourcePackage = sourcePackage,
                ownPackage = ownPackage,
                notification = notification,
                isOngoing = isOngoing,
                isGroupSummary = isGroupSummary,
                isForegroundService = isForegroundService,
                isClearable = isClearable,
                importance = importance,
                isAmbient = isAmbient,
                matchesInterruptionFilter = matchesInterruptionFilter,
                isMutedOrSuppressed = isMutedOrSuppressed,
                effectiveVisibility = effectiveVisibility,
                settings = _settingsCache.value
            )
        } else {
            // Cold-start path before initial DataStore emission completes: avoid blocking disk I/O
            // on the listener binder/main callback while ensuring early boot alerts are not lost.
            val preliminaryCandidate = NotificationCandidate(
                packageName = sourcePackage,
                ownPackageName = ownPackage,
                monitoredPackages = setOf(sourcePackage),
                isOngoing = isOngoing,
                isGroupSummary = isGroupSummary,
                isForegroundService = isForegroundService,
                isClearable = isClearable,
                category = notification.category,
                importance = importance,
                isAmbient = isAmbient,
                matchesInterruptionFilter = matchesInterruptionFilter,
                isMutedOrSuppressed = isMutedOrSuppressed
            )
            if (!filterEngine.shouldProcess(preliminaryCandidate)) {
                return
            }

            val extractedSnapshot = extractNotificationSnapshot(notification, effectiveVisibility)
            scope.launch {
                val resolvedSettings = awaitInitialSettings()
                processSnapshotWithSettings(
                    sbnKey = sbnKey,
                    sourcePackage = sourcePackage,
                    ownPackage = ownPackage,
                    category = notification.category,
                    isOngoing = isOngoing,
                    isGroupSummary = isGroupSummary,
                    isForegroundService = isForegroundService,
                    isClearable = isClearable,
                    importance = importance,
                    isAmbient = isAmbient,
                    matchesInterruptionFilter = matchesInterruptionFilter,
                    isMutedOrSuppressed = isMutedOrSuppressed,
                    snapshot = extractedSnapshot,
                    settings = resolvedSettings
                )
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap
    ) {
        val key = sbn.key?.trim().orEmpty()
        if (key.isNotEmpty()) {
            deduplicator.onNotificationRemoved(key)
        }
    }

    override fun onDestroy() {
        settingsCollectionJob?.cancel()
        settingsCollectionJob = null
        cancelAllBatches()
        deduplicator.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCollectingSettings() {
        val container = appContainerOrNull ?: return
        settingsCollectionJob?.cancel()
        settingsCollectionJob = scope.launch {
            container.settingsRepository.settings.collect { latest ->
                _settingsCache.value = latest
                settingsLoaded.set(true)
            }
        }
    }

    private suspend fun awaitInitialSettings(): UserSettings {
        if (settingsLoaded.get()) {
            return _settingsCache.value
        }
        val repo = appContainerOrNull?.settingsRepository ?: return _settingsCache.value
        val fetched = withTimeoutOrNull(INITIAL_SETTINGS_WAIT_TIMEOUT_MS) {
            runCatching { repo.settings.first() }.getOrNull()
        }
        if (fetched != null) {
            _settingsCache.value = fetched
            settingsLoaded.set(true)
            return fetched
        }
        return _settingsCache.value
    }

    private fun processWithSettings(
        sbnKey: String,
        sourcePackage: String,
        ownPackage: String,
        notification: Notification,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isForegroundService: Boolean,
        isClearable: Boolean,
        importance: Int,
        isAmbient: Boolean,
        matchesInterruptionFilter: Boolean,
        isMutedOrSuppressed: Boolean,
        effectiveVisibility: Int,
        settings: UserSettings
    ) {
        val candidate = NotificationCandidate(
            packageName = sourcePackage,
            ownPackageName = ownPackage,
            monitoredPackages = settings.monitoredPackages,
            isOngoing = isOngoing,
            isGroupSummary = isGroupSummary,
            isForegroundService = isForegroundService,
            isClearable = isClearable,
            category = notification.category,
            importance = importance,
            isAmbient = isAmbient,
            matchesInterruptionFilter = matchesInterruptionFilter,
            isMutedOrSuppressed = isMutedOrSuppressed
        )
        if (!filterEngine.shouldProcess(candidate)) {
            return
        }

        val snapshot = extractNotificationSnapshot(notification, effectiveVisibility)
        dispatchExtractedSnapshot(
            sbnKey = sbnKey,
            sourcePackage = sourcePackage,
            snapshot = snapshot,
            settings = settings
        )
    }

    private fun processSnapshotWithSettings(
        sbnKey: String,
        sourcePackage: String,
        ownPackage: String,
        category: String?,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isForegroundService: Boolean,
        isClearable: Boolean,
        importance: Int,
        isAmbient: Boolean,
        matchesInterruptionFilter: Boolean,
        isMutedOrSuppressed: Boolean,
        snapshot: ExtractedNotificationSnapshot,
        settings: UserSettings
    ) {
        val candidate = NotificationCandidate(
            packageName = sourcePackage,
            ownPackageName = ownPackage,
            monitoredPackages = settings.monitoredPackages,
            isOngoing = isOngoing,
            isGroupSummary = isGroupSummary,
            isForegroundService = isForegroundService,
            isClearable = isClearable,
            category = category,
            importance = importance,
            isAmbient = isAmbient,
            matchesInterruptionFilter = matchesInterruptionFilter,
            isMutedOrSuppressed = isMutedOrSuppressed
        )
        if (!filterEngine.shouldProcess(candidate)) {
            return
        }

        dispatchExtractedSnapshot(
            sbnKey = sbnKey,
            sourcePackage = sourcePackage,
            snapshot = snapshot,
            settings = settings
        )
    }

    private fun extractNotificationSnapshot(
        notification: Notification,
        effectiveVisibility: Int
    ): ExtractedNotificationSnapshot {
        val extras = notification.extras ?: Bundle.EMPTY

        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        val messagingMessages = messagingStyle?.messages.orEmpty().map { msg ->
            MessagingMessageCandidate(
                sender = msg.person?.name?.toString().orEmpty(),
                text = msg.text?.toString().orEmpty(),
                timestamp = msg.timestamp
            )
        }
        val messagingConversationTitle = messagingStyle?.conversationTitle?.toString().orEmpty()
        val isGroupConversation = messagingStyle?.isGroupConversation == true

        val inboxLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { line ->
                line?.toString()?.let(MessagingStyleDeduplicator::normalizeLine)?.takeIf(String::isNotEmpty)
            }
            .orEmpty()

        val extraConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString().orEmpty()
        val extraTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString().orEmpty()
        val extraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString().orEmpty()
        val extraText = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString().orEmpty()

        val publicExtras = notification.publicVersion?.extras
        val publicTitle = publicExtras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val publicText = sequenceOf(
            publicExtras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
            publicExtras?.getCharSequence(Notification.EXTRA_TEXT)
        ).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()

        return ExtractedNotificationSnapshot(
            effectiveVisibility = effectiveVisibility,
            hasMessagingStyle = messagingStyle != null,
            isGroupConversation = isGroupConversation,
            messagingConversationTitle = messagingConversationTitle,
            messagingMessages = messagingMessages,
            inboxLines = inboxLines,
            extraConversationTitle = extraConversationTitle,
            extraTitle = extraTitle,
            extraBigText = extraBigText,
            extraText = extraText,
            publicTitle = publicTitle,
            publicText = publicText
        )
    }

    private fun dispatchExtractedSnapshot(
        sbnKey: String,
        sourcePackage: String,
        snapshot: ExtractedNotificationSnapshot,
        settings: UserSettings
    ) {
        val appName = resolveAppLabel(sourcePackage)
        val detector = redactionDetector

        val primaryTitle = sequenceOf(
            snapshot.messagingConversationTitle,
            snapshot.extraConversationTitle,
            snapshot.extraTitle
        ).map(::sanitizeSender)
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        val primaryBody = when {
            snapshot.messagingMessages.isNotEmpty() ->
                snapshot.messagingMessages.lastOrNull()?.text.orEmpty()
            snapshot.inboxLines.isNotEmpty() ->
                snapshot.inboxLines.joinToString("\n")
            snapshot.extraBigText.isNotBlank() ->
                snapshot.extraBigText
            else ->
                snapshot.extraText
        }

        val redactionResult = detector.detect(
            RedactionCandidate(
                appName = appName,
                visibility = snapshot.effectiveVisibility,
                extractedTitle = primaryTitle,
                extractedText = primaryBody,
                extractedMessages = snapshot.messagingMessages.map { it.text },
                publicVersionTitle = snapshot.publicTitle,
                publicVersionText = snapshot.publicText
            )
        )

        if (redactionResult.isRedacted) {
            val fallbackPrompt = sanitizeMessage(
                redactionResult.fallbackMessage
                    ?: getString(R.string.redacted_alert_spoken_prompt, appName)
            )
            val deduplicatedFallback = deduplicator.filterNewLines(sbnKey, listOf(fallbackPrompt))
            if (deduplicatedFallback.isEmpty()) {
                return
            }

            val safeSender = if (
                snapshot.effectiveVisibility != RedactionDetector.VISIBILITY_SECRET &&
                primaryTitle.isNotEmpty() &&
                !detector.isRedactedPhrase(primaryTitle) &&
                !primaryTitle.equals(appName, ignoreCase = true)
            ) {
                primaryTitle
            } else {
                ""
            }

            enqueueDebouncedBatch(
                sourcePackage = sourcePackage,
                appName = appName,
                batchGroupKey = safeSender,
                senderDisplay = safeSender,
                newMessages = deduplicatedFallback,
                settings = settings
            )
            return
        }

        // Branch 1: MessagingStyle incremental extraction via timestamp high-water mark.
        if (snapshot.hasMessagingStyle && snapshot.messagingMessages.isNotEmpty()) {
            val freshMessages = deduplicator.filterNewMessagingMessages(
                sbnKey = sbnKey,
                messages = snapshot.messagingMessages
            )
            if (freshMessages.isEmpty()) {
                return
            }

            val convTitle = sanitizeSender(
                snapshot.messagingConversationTitle.ifBlank { snapshot.extraConversationTitle }
            )
            val fallbackSender = sanitizeSender(snapshot.extraTitle)
            val distinctSenders = freshMessages
                .map { sanitizeSender(it.sender) }
                .filter { it.isNotEmpty() }
                .distinct()

            val isGroupContext = convTitle.isNotEmpty() && (
                snapshot.isGroupConversation ||
                    distinctSenders.size > 1 ||
                    (distinctSenders.size == 1 && !distinctSenders.single().equals(convTitle, ignoreCase = true))
                )

            if (isGroupContext) {
                val formattedItems = freshMessages.mapNotNull { item ->
                    val body = sanitizeMessage(item.text)
                    if (body.isEmpty()) return@mapNotNull null
                    val itemSender = sanitizeSender(item.sender)
                    if (itemSender.isNotEmpty() && !itemSender.equals(convTitle, ignoreCase = true)) {
                        "$itemSender: $body"
                    } else {
                        body
                    }
                }
                if (formattedItems.isEmpty()) return

                enqueueDebouncedBatch(
                    sourcePackage = sourcePackage,
                    appName = appName,
                    batchGroupKey = convTitle,
                    senderDisplay = convTitle,
                    newMessages = formattedItems,
                    settings = settings
                )
            } else {
                val directSender = distinctSenders.lastOrNull()
                    .orEmpty()
                    .ifEmpty { convTitle.ifEmpty { fallbackSender } }
                val cleanBodies = freshMessages
                    .map { sanitizeMessage(it.text) }
                    .filter { it.isNotEmpty() }
                if (cleanBodies.isEmpty()) return

                enqueueDebouncedBatch(
                    sourcePackage = sourcePackage,
                    appName = appName,
                    batchGroupKey = directSender,
                    senderDisplay = directSender,
                    newMessages = cleanBodies,
                    settings = settings
                )
            }
            return
        }

        // Branch 2: InboxStyle line-diffing.
        if (snapshot.inboxLines.isNotEmpty()) {
            val freshLines = deduplicator.filterNewLines(sbnKey, snapshot.inboxLines)
                .map(::sanitizeMessage)
                .filter { it.isNotEmpty() }
            if (freshLines.isEmpty()) {
                return
            }
            val sender = sanitizeSender(
                snapshot.extraConversationTitle.ifBlank { snapshot.extraTitle }
            )
            enqueueDebouncedBatch(
                sourcePackage = sourcePackage,
                appName = appName,
                batchGroupKey = sender,
                senderDisplay = sender,
                newMessages = freshLines,
                settings = settings
            )
            return
        }

        // Branch 3: BigTextStyle / standard EXTRA_TEXT line-diffing.
        val rawBody = snapshot.extraBigText.takeIf { it.isNotBlank() } ?: snapshot.extraText
        val freshLines = deduplicator.filterNewTextBlock(sbnKey, rawBody)
            .map(::sanitizeMessage)
            .filter { it.isNotEmpty() }
        if (freshLines.isEmpty()) {
            return
        }

        val sender = sanitizeSender(
            snapshot.extraConversationTitle.ifBlank { snapshot.extraTitle }
        )
        enqueueDebouncedBatch(
            sourcePackage = sourcePackage,
            appName = appName,
            batchGroupKey = sender,
            senderDisplay = sender,
            newMessages = freshLines,
            settings = settings
        )
    }

    private fun enqueueDebouncedBatch(
        sourcePackage: String,
        appName: String,
        batchGroupKey: String,
        senderDisplay: String,
        newMessages: List<String>,
        settings: UserSettings
    ) {
        if (newMessages.isEmpty()) return

        val key = BatchKey(packageName = sourcePackage, groupKey = batchGroupKey)
        val existing = batches[key]
        if (existing != null) {
            appendMessagesBounded(existing.messages, newMessages)
            return
        }

        val debounceMs = UserSettings.clampDebounceWindowMs(settings.debounceWindowMs)
        val ringTimeoutMs = UserSettings.clampRingTimeoutSeconds(settings.ringTimeoutSeconds) * 1_000L
        val accumulatedMessages = ArrayList<String>(newMessages.size)
        appendMessagesBounded(accumulatedMessages, newMessages)

        val wakeLock = acquireBoundedWakeLock()
        lateinit var createdBatch: PendingBatch

        val flushJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                if (batches[key] === createdBatch) {
                    batches.remove(key)
                }
                val consolidatedBody = sanitizeMessage(accumulatedMessages.joinToString(". "))
                if (consolidatedBody.isNotEmpty()) {
                    val payload = AlertPayload(
                        appName = appName,
                        packageName = sourcePackage,
                        sender = senderDisplay,
                        message = consolidatedBody
                    )
                    val callManager = appContainerOrNull?.localCallManager
                        ?: LocalCallManager.getInstance(applicationContext)
                    callManager.enqueueCall(
                        payload = payload,
                        ringTimeoutMs = ringTimeoutMs
                    )
                }
            } finally {
                if (batches[key] === createdBatch) {
                    batches.remove(key)
                }
                releaseWakeLockSafely(wakeLock)
            }
        }

        createdBatch = PendingBatch(
            messages = accumulatedMessages,
            wakeLock = wakeLock,
            job = flushJob
        )
        batches[key] = createdBatch
        flushJob.start()
    }

    private fun appendMessagesBounded(target: MutableList<String>, additions: List<String>) {
        for (msg in additions) {
            if (target.size >= MAX_MESSAGES_PER_BATCH) break
            target.add(msg)
        }
    }

    private fun cancelAllBatches() {
        val active = batches.values.toList()
        batches.clear()
        for (batch in active) {
            batch.job.cancel()
            releaseWakeLockSafely(batch.wakeLock)
        }
    }

    private fun acquireBoundedWakeLock(): PowerManager.WakeLock? {
        val powerManager = getSystemService(PowerManager::class.java) ?: return null
        return runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        runCatching {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun refreshRedactionDetector() {
        val systemPhrases = resolveSystemRedactedPhrases()
        val unknownAppFallback = runCatching {
            getString(R.string.listener_unknown_app_name)
        }.getOrDefault(RedactionDetector.DEFAULT_APP_LABEL)

        redactionDetector = RedactionDetector(
            systemRedactedPhrases = systemPhrases,
            defaultAppLabel = unknownAppFallback,
            fallbackFormatter = { safeApp ->
                getString(R.string.redacted_alert_spoken_prompt, safeApp)
            }
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveSystemRedactedPhrases(): Set<String> {
        val resolved = mutableSetOf<String>()
        val resourceSources = listOfNotNull(Resources.getSystem(), resources)
        for (res in resourceSources) {
            for (entryName in SYSTEM_REDACTED_STRING_NAMES) {
                runCatching {
                    val resId = res.getIdentifier(entryName, "string", "android")
                    if (resId != 0) {
                        val phrase = res.getString(resId).trim()
                        if (phrase.isNotEmpty()) {
                            resolved.add(phrase)
                        }
                    }
                }
            }
        }
        return resolved
    }

    private fun resolveAppLabel(targetPackage: String): String {
        if (targetPackage.isBlank()) {
            return getString(R.string.listener_unknown_app_name)
        }
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    targetPackage,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(targetPackage, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString().trim().ifEmpty { targetPackage }
        } catch (_: PackageManager.NameNotFoundException) {
            targetPackage
        } catch (_: Exception) {
            targetPackage
        }
    }

    private fun sanitizeSender(raw: String): String {
        val cleaned = MessagingStyleDeduplicator.normalizeLine(raw)
        if (cleaned.length <= MAX_SENDER_CHARS) return cleaned
        return cleaned.substring(0, MAX_SENDER_CHARS).trim()
    }

    private fun sanitizeMessage(raw: String): String {
        val cleaned = MessagingStyleDeduplicator.normalizeLine(raw)
        if (cleaned.length <= MAX_MESSAGE_CHARS) return cleaned
        return cleaned.substring(0, MAX_MESSAGE_CHARS).trim()
    }

    private data class BatchKey(
        val packageName: String,
        val groupKey: String
    )

    private class PendingBatch(
        val messages: MutableList<String>,
        val wakeLock: PowerManager.WakeLock?,
        val job: Job
    )

    private data class ExtractedNotificationSnapshot(
        val effectiveVisibility: Int,
        val hasMessagingStyle: Boolean,
        val isGroupConversation: Boolean,
        val messagingConversationTitle: String,
        val messagingMessages: List<MessagingMessageCandidate>,
        val inboxLines: List<String>,
        val extraConversationTitle: String,
        val extraTitle: String,
        val extraBigText: String,
        val extraText: String,
        val publicTitle: String?,
        val publicText: String?
    )

    companion object {
        internal const val WAKE_LOCK_TAG: String = "NotifyRing:AlertDebounce"
        internal const val WAKE_LOCK_TIMEOUT_MS: Long = 10_000L
        private const val INITIAL_SETTINGS_WAIT_TIMEOUT_MS: Long = 2_000L
        private const val MAX_REBIND_ATTEMPTS: Int = 5
        private const val MAX_SENDER_CHARS: Int = 120
        private const val MAX_MESSAGE_CHARS: Int = 500
        private const val MAX_MESSAGES_PER_BATCH: Int = 10

        private val SYSTEM_REDACTED_STRING_NAMES: List<String> = listOf(
            "redacted_notification_message",
            "notification_hidden_text"
        )
    }
}
