package com.notifyring.app.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.notifyring.app.service.AlertNotificationListenerService
import com.notifyring.app.telecom.CallForegroundService

/**
 * Evaluates live Android permissions, special access grants, and notification channel health into
 * a [PermissionSnapshot], and provides Play-safe system settings intent launchers with OEM fallbacks.
 */
object AndroidPermissionInspector {

    fun inspect(context: Context): PermissionSnapshot {
        val appContext = context.applicationContext
        val sdkInt = Build.VERSION.SDK_INT
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)

        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationsEnabledInSystem = NotificationManagerCompat
            .from(appContext)
            .areNotificationsEnabled()

        val fullScreenIntentGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                notificationManager?.canUseFullScreenIntent() ?: false
            } else {
                true
            }

        val bluetoothConnectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // If the channel hasn't been created yet (before first call), treat as healthy so we don't
        // raise a false degraded warning on a fresh install.
        val alertChannel = notificationManager?.getNotificationChannel(CallForegroundService.CHANNEL_ID)
        val alertChannelImportanceHigh = alertChannel == null ||
            alertChannel.importance >= NotificationManager.IMPORTANCE_HIGH

        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val ignoringBatteryOptimizations = powerManager
            ?.isIgnoringBatteryOptimizations(appContext.packageName)
            ?: false

        return PermissionSnapshot(
            sdkInt = sdkInt,
            notificationListenerEnabled = listenerEnabled,
            postNotificationsGranted = postNotificationsGranted,
            notificationsEnabledInSystem = notificationsEnabledInSystem,
            fullScreenIntentGranted = fullScreenIntentGranted,
            bluetoothConnectGranted = bluetoothConnectGranted,
            alertChannelImportanceHigh = alertChannelImportanceHigh,
            ignoringBatteryOptimizations = ignoringBatteryOptimizations
        )
    }

    fun openNotificationListenerSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(context, AlertNotificationListenerService::class.java)
            val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component.flattenToString()
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(context, detailIntent)) {
                return
            }
        }
        val listIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStartActivity(context, listIntent)) {
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStartActivity(context, intent)
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStartActivity(context, intent)) {
            openAppDetailsSettings(context)
        }
    }

    fun openAlertChannelSettings(context: Context) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CallForegroundService.CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStartActivity(context, intent)) {
            openAppNotificationSettings(context)
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(context, intent)) {
                return
            }
        }
        openAppDetailsSettings(context)
    }

    /**
     * Play-safe battery guidance intent: opens the system Battery Optimization list without
     * requesting any Play-restricted battery exemption permission.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStartActivity(context, intent)) {
            openAppDetailsSettings(context)
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
