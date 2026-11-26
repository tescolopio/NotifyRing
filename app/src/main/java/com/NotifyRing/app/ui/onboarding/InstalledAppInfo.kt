package com.notifyring.app.ui.onboarding

/**
 * Pure-Kotlin metadata for a user-launchable app discovered via `MAIN`/`LAUNCHER` intent query.
 */
data class InstalledAppInfo(
    val packageName: String,
    val label: String
)
