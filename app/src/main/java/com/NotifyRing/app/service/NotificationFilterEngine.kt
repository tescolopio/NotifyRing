package com.notifyring.app.service

import java.util.Locale

/**
 * Immutable, pure-Kotlin snapshot of an incoming status bar notification and its ranking signals.
 * Kept completely free of Android framework classes so unit tests run directly on the JVM.
 */
data class NotificationCandidate(
    val packageName: String,
    val ownPackageName: String,
    val monitoredPackages: Set<String>,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isForegroundService: Boolean = false,
    val isClearable: Boolean = true,
    val category: String? = null,
    val importance: Int = NotificationFilterEngine.IMPORTANCE_DEFAULT,
    val isAmbient: Boolean = false,
    val matchesInterruptionFilter: Boolean = true,
    val isMutedOrSuppressed: Boolean = false
)

/**
 * Explicit reason why [NotificationFilterEngine] rejected a [NotificationCandidate].
 */
enum class FilterRejectionReason {
    BLANK_PACKAGE,
    SELF_PACKAGE,
    NOT_MONITORED,
    GROUP_SUMMARY,
    ONGOING,
    FOREGROUND_SERVICE,
    NON_CLEARABLE,
    BLOCKED_CATEGORY,
    LOW_IMPORTANCE,
    AMBIENT,
    INTERRUPTION_FILTER_SUPPRESSED,
    MUTED_OR_SUPPRESSED
}

/**
 * Result of evaluating a [NotificationCandidate] against [NotificationFilterEngine] guardrails.
 */
sealed interface FilterDecision {
    val isAccepted: Boolean

    data object Accepted : FilterDecision {
        override val isAccepted: Boolean = true
    }

    data class Rejected(val reason: FilterRejectionReason) : FilterDecision {
        override val isAccepted: Boolean = false
    }
}

/**
 * Stateless, pure-Kotlin notification filter engine enforcing API 29-35 listener guardrails:
 * 1. Self-package & blank-package loop protection
 * 2. User-configured monitored package allowlist
 * 3. Group summary, ongoing event, foreground service, and non-clearable rejection
 * 4. Call / VoIP / progress / service / transport / system / status category rejection
 * 5. Silent / ambient / muted / DND-suppressed ranking rejection
 */
class NotificationFilterEngine {

    fun evaluate(candidate: NotificationCandidate): FilterDecision {
        val pkg = candidate.packageName.trim()
        if (pkg.isEmpty()) {
            return FilterDecision.Rejected(FilterRejectionReason.BLANK_PACKAGE)
        }

        val ownPkg = candidate.ownPackageName.trim()
        if (ownPkg.isNotEmpty() && pkg == ownPkg) {
            return FilterDecision.Rejected(FilterRejectionReason.SELF_PACKAGE)
        }

        if (pkg !in candidate.monitoredPackages) {
            return FilterDecision.Rejected(FilterRejectionReason.NOT_MONITORED)
        }

        if (candidate.isGroupSummary) {
            return FilterDecision.Rejected(FilterRejectionReason.GROUP_SUMMARY)
        }

        if (candidate.isOngoing) {
            return FilterDecision.Rejected(FilterRejectionReason.ONGOING)
        }

        if (candidate.isForegroundService) {
            return FilterDecision.Rejected(FilterRejectionReason.FOREGROUND_SERVICE)
        }

        if (!candidate.isClearable) {
            return FilterDecision.Rejected(FilterRejectionReason.NON_CLEARABLE)
        }

        val normalizedCategory = candidate.category
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (normalizedCategory != null && normalizedCategory in BLOCKED_CATEGORIES) {
            return FilterDecision.Rejected(FilterRejectionReason.BLOCKED_CATEGORY)
        }

        if (candidate.importance < IMPORTANCE_DEFAULT) {
            return FilterDecision.Rejected(FilterRejectionReason.LOW_IMPORTANCE)
        }

        if (candidate.isAmbient) {
            return FilterDecision.Rejected(FilterRejectionReason.AMBIENT)
        }

        if (!candidate.matchesInterruptionFilter) {
            return FilterDecision.Rejected(FilterRejectionReason.INTERRUPTION_FILTER_SUPPRESSED)
        }

        if (candidate.isMutedOrSuppressed) {
            return FilterDecision.Rejected(FilterRejectionReason.MUTED_OR_SUPPRESSED)
        }

        return FilterDecision.Accepted
    }

    fun shouldProcess(candidate: NotificationCandidate): Boolean =
        evaluate(candidate).isAccepted

    companion object {
        // Mirrors android.app.NotificationManager importance constants.
        const val IMPORTANCE_NONE: Int = 0
        const val IMPORTANCE_MIN: Int = 1
        const val IMPORTANCE_LOW: Int = 2
        const val IMPORTANCE_DEFAULT: Int = 3
        const val IMPORTANCE_HIGH: Int = 4
        const val IMPORTANCE_MAX: Int = 5

        // Mirrors android.app.Notification.CATEGORY_* string constants.
        const val CATEGORY_CALL: String = "call"
        const val CATEGORY_VOIP: String = "voip"
        const val CATEGORY_PROGRESS: String = "progress"
        const val CATEGORY_SERVICE: String = "service"
        const val CATEGORY_TRANSPORT: String = "transport"
        const val CATEGORY_SYSTEM: String = "sys"
        const val CATEGORY_SYSTEM_ALIAS: String = "system"
        const val CATEGORY_STATUS: String = "status"

        val BLOCKED_CATEGORIES: Set<String> = setOf(
            CATEGORY_CALL,
            CATEGORY_VOIP,
            CATEGORY_PROGRESS,
            CATEGORY_SERVICE,
            CATEGORY_TRANSPORT,
            CATEGORY_SYSTEM,
            CATEGORY_SYSTEM_ALIAS,
            CATEGORY_STATUS
        )
    }
}
