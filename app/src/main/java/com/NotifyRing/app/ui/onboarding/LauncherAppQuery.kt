package com.notifyring.app.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Discovers installed launchable apps using ONLY the Play-safe `ACTION_MAIN` + `CATEGORY_LAUNCHER`
 * intent query (matching `<queries>` in AndroidManifest.xml with no broad package-visibility permission).
 */
object LauncherAppQuery {

    fun queryLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val selfPackage = context.packageName
        val seenPackages = LinkedHashMap<String, InstalledAppInfo>()
        for (info in resolveInfos) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == selfPackage || seenPackages.containsKey(pkg)) continue
            val rawLabel = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
            val label = rawLabel?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            seenPackages[pkg] = InstalledAppInfo(packageName = pkg, label = label)
        }

        return seenPackages.values.sortedWith(
            compareBy<InstalledAppInfo> { it.label.lowercase() }
                .thenBy { it.packageName.lowercase() }
        )
    }

    /**
     * Pure-Kotlin search and sort helper: filters by label or package name and keeps selected
     * apps visible at the top when no search query is active.
     */
    fun filterAndSortApps(
        apps: List<InstalledAppInfo>,
        query: String,
        selectedPackages: Set<String>
    ): List<InstalledAppInfo> {
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase().contains(trimmed) ||
                    app.packageName.lowercase().contains(trimmed)
            }
        }
        return filtered.sortedWith(
            compareByDescending<InstalledAppInfo> { it.packageName in selectedPackages }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName.lowercase() }
        )
    }
}
