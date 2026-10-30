package com.example.novamusic

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

object SleepTimerCommands {
    const val PREFERENCES_NAME = "nova_sleep_timer_prefs"
    const val SET_TIMER = "com.example.novamusic.SET_SLEEP_TIMER"
    const val CANCEL_TIMER = "com.example.novamusic.CANCEL_SLEEP_TIMER"
    const val END_TIME_KEY = "sleep_timer_end_time"
    const val DURATION_KEY = "sleep_timer_duration"
    const val MINUTES_KEY = "sleep_timer_minutes"
}

object EqualizerCommands {
    const val GET_INFO = "com.example.novamusic.GET_EQ_INFO"
    const val SET_ENABLED = "com.example.novamusic.SET_EQ_ENABLED"
    const val ENABLED_KEY = "eq_enabled_key"
    const val SET_BAND = "com.example.novamusic.SET_EQ_BAND"
    const val BAND_INDEX_KEY = "eq_band_index"
    const val BAND_LEVEL_KEY = "eq_band_level"
    const val SET_PRESET = "com.example.novamusic.SET_EQ_PRESET"
    const val PRESET_INDEX_KEY = "eq_preset_index"
    const val SET_BASS = "com.example.novamusic.SET_BASS"
    const val STRENGTH_KEY = "effect_strength"
    const val SET_VIRTUALIZER = "com.example.novamusic.SET_VIRTUALIZER"
}

class PlaybackService : MediaSessionService() {
    private lateinit var mediaSession: MediaSession
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null
    private val sleepTimerPrefs: SharedPreferences by lazy {
        getSharedPreferences(SleepTimerCommands.PREFERENCES_NAME, MODE_PRIVATE)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()

        try {
            val audioSessionId = player.audioSessionId
            equalizer = Equalizer(0, audioSessionId).apply { enabled = false }
            bassBoost = BassBoost(0, audioSessionId).apply { enabled = false }
            virtualizer = Virtualizer(0, audioSessionId).apply { enabled = false }
        } catch (_: Exception) {
            // Audio effects might not be supported on some devices/emulators
        }

        val callback = object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val action = customCommand.customAction
                val responseBundle = Bundle()

                when (action) {
                    SleepTimerCommands.SET_TIMER -> {
                        val minutes = args.getInt(SleepTimerCommands.MINUTES_KEY, 0)
                        if (minutes > 0) {
                            startSleepTimer(minutes)
                        } else {
                            cancelSleepTimer()
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    SleepTimerCommands.CANCEL_TIMER -> {
                        cancelSleepTimer()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    EqualizerCommands.GET_INFO -> {
                        val eq = equalizer
                        responseBundle.putBoolean("eq_available", eq != null)
                        responseBundle.putBoolean("eq_enabled", eq?.enabled == true)
                        if (eq != null) {
                            responseBundle.putInt("band_count", eq.numberOfBands.toInt())
                            val minMax = eq.bandLevelRange
                            responseBundle.putInt("min_level_mb", minMax[0].toInt())
                            responseBundle.putInt("max_level_mb", minMax[1].toInt())

                            val bands = eq.numberOfBands.toInt()
                            val freqs = IntArray(bands)
                            val levels = IntArray(bands)
                            for (i in 0 until bands) {
                                freqs[i] = eq.getCenterFreq(i.toShort()).toInt()
                                levels[i] = eq.getBandLevel(i.toShort()).toInt()
                            }
                            responseBundle.putIntArray("band_frequencies_hz", freqs)
                            responseBundle.putIntArray("band_levels_mb", levels)

                            val presets = mutableListOf<String>()
                            val numPresets = eq.numberOfPresets
                            for (p in 0 until numPresets) {
                                presets.add(eq.getPresetName(p.toShort()))
                            }
                            responseBundle.putStringArray("preset_names", presets.toTypedArray())
                            responseBundle.putInt("selected_preset", eq.currentPreset.toInt())
                        }

                        val bb = bassBoost
                        responseBundle.putBoolean("bass_available", bb != null)
                        responseBundle.putBoolean("bass_enabled", bb?.enabled == true)
                        responseBundle.putInt("bass_strength", if (bb?.enabled == true) bb.roundedStrength.toInt() else 0)

                        val virt = virtualizer
                        responseBundle.putBoolean("virtualizer_available", virt != null)
                        responseBundle.putBoolean("virtualizer_enabled", virt?.enabled == true)
                        responseBundle.putInt("virtualizer_strength", if (virt?.enabled == true) virt.roundedStrength.toInt() else 0)

                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, responseBundle))
                    }
                    EqualizerCommands.SET_ENABLED -> {
                        val enabled = args.getBoolean(EqualizerCommands.ENABLED_KEY, false)
                        equalizer?.enabled = enabled
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    EqualizerCommands.SET_BAND -> {
                        val bandIndex = args.getInt(EqualizerCommands.BAND_INDEX_KEY, -1)
                        val level = args.getInt(EqualizerCommands.BAND_LEVEL_KEY, 0)
                        if (bandIndex >= 0 && equalizer != null) {
                            try {
                                equalizer?.setBandLevel(bandIndex.toShort(), level.toShort())
                            } catch (_: Exception) {
                                return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    EqualizerCommands.SET_PRESET -> {
                        val presetIndex = args.getInt(EqualizerCommands.PRESET_INDEX_KEY, -1)
                        if (presetIndex >= 0 && equalizer != null) {
                            try {
                                equalizer?.usePreset(presetIndex.toShort())
                            } catch (_: Exception) {
                                return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    EqualizerCommands.SET_BASS -> {
                        val enabled = args.getBoolean(EqualizerCommands.ENABLED_KEY, false)
                        val strength = args.getInt(EqualizerCommands.STRENGTH_KEY, 0)
                        bassBoost?.let {
                            it.enabled = enabled
                            if (enabled) {
                                try {
                                    it.setStrength(strength.toShort())
                                } catch (_: Exception) {}
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    EqualizerCommands.SET_VIRTUALIZER -> {
                        val enabled = args.getBoolean(EqualizerCommands.ENABLED_KEY, false)
                        val strength = args.getInt(EqualizerCommands.STRENGTH_KEY, 0)
                        virtualizer?.let {
                            it.enabled = enabled
                            if (enabled) {
                                try {
                                    it.setStrength(strength.toShort())
                                } catch (_: Exception) {}
                            }
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()

        checkStoredSleepTimer()
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val durationMs = minutes * 60_000L
        val endTime = System.currentTimeMillis() + durationMs
        sleepTimerPrefs.edit()
            .putLong(SleepTimerCommands.END_TIME_KEY, endTime)
            .putInt(SleepTimerCommands.DURATION_KEY, minutes)
            .apply()

        sleepTimerRunnable = Runnable {
            if (player.isPlaying) {
                player.pause()
            }
            cancelSleepTimer()
        }
        handler.postDelayed(sleepTimerRunnable!!, durationMs)
    }

    private fun cancelSleepTimer() {
        sleepTimerRunnable?.let { handler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerPrefs.edit()
            .remove(SleepTimerCommands.END_TIME_KEY)
            .remove(SleepTimerCommands.DURATION_KEY)
            .apply()
    }

    private fun checkStoredSleepTimer() {
        val endTime = sleepTimerPrefs.getLong(SleepTimerCommands.END_TIME_KEY, 0L)
        val remaining = endTime - System.currentTimeMillis()
        if (endTime > 0L) {
            if (remaining > 0L) {
                sleepTimerRunnable = Runnable {
                    if (player.isPlaying) {
                        player.pause()
                    }
                    cancelSleepTimer()
                }
                handler.postDelayed(sleepTimerRunnable!!, remaining)
            } else {
                cancelSleepTimer()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        cancelSleepTimer()
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
        } catch (_: Exception) {}
        mediaSession.run {
            player.release()
            release()
        }
        super.onDestroy()
    }
}
