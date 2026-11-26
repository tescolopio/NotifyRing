package com.notifyring.app.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

/**
 * Manages looping system ringtone playback (`USAGE_NOTIFICATION_RINGTONE`) and waveform vibration
 * while an alert call is ringing, guaranteeing deterministic teardown on Answer, Disconnect, or
 * Service destruction across API 29–35.
 */
class CallRingtoneController(
    context: Context
) {
    private val appContext: Context = context.applicationContext
    private val lock = Any()

    private var activeRingtone: Ringtone? = null
    private var activeVibrator: Vibrator? = null
    private var isRinging: Boolean = false

    fun startRinging() {
        synchronized(lock) {
            if (isRinging) return
            isRinging = true

            val audioManager = appContext.getSystemService(AudioManager::class.java)
            val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return
            }

            val ringtoneAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                startLoopingRingtone(ringtoneAttributes)
            }
            startWaveformVibration(ringtoneAttributes)
        }
    }

    fun stopRinging() {
        synchronized(lock) {
            isRinging = false
            val ringtone = activeRingtone
            activeRingtone = null
            runCatching {
                ringtone?.stop()
            }

            val vibrator = activeVibrator
            activeVibrator = null
            cancelVibrationSafely(vibrator)
        }
    }

    private fun startLoopingRingtone(attributes: AudioAttributes) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
            ringtone.audioAttributes = attributes
            ringtone.isLooping = true
            ringtone.play()
            activeRingtone = ringtone
        }
    }

    private fun startWaveformVibration(attributes: AudioAttributes) {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.VIBRATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            val waveform = VibrationEffect.createWaveform(RINGTONE_VIBRATION_TIMINGS, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val vibrationAttrs = VibrationAttributes.createForUsage(
                    VibrationAttributes.USAGE_RINGTONE
                )
                vibrator.vibrate(waveform, vibrationAttrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(waveform, attributes)
            }
            activeVibrator = vibrator
        }
    }

    private fun cancelVibrationSafely(vibrator: Vibrator?) {
        if (vibrator == null) return
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.VIBRATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            vibrator.cancel()
        }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
    }

    companion object {
        private val RINGTONE_VIBRATION_TIMINGS = longArrayOf(0L, 800L, 600L, 800L, 1_200L)
    }
}
