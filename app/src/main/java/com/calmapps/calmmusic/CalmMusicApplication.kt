package com.calmapps.calmmusic

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.calmapps.calmmusic.data.CalmMusicSettingsManager
import com.calmapps.calmmusic.data.NowPlayingStorage
import com.calmapps.calmmusic.data.PlaybackStateManager
import com.calmapps.calmmusic.overlay.SystemOverlayService

@UnstableApi
class CalmMusic : Application(), DefaultLifecycleObserver {

    val playbackStateManager: PlaybackStateManager by lazy {
        PlaybackStateManager()
    }

    val nowPlayingStorage: NowPlayingStorage by lazy {
        NowPlayingStorage(this)
    }

    lateinit var settingsManager: CalmMusicSettingsManager
        private set

    override fun onCreate() {
        super<Application>.onCreate()

        settingsManager = CalmMusicSettingsManager(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        playbackStateManager.setAppForegroundState(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        playbackStateManager.setAppForegroundState(false)

        val overlayState = playbackStateManager.state.value
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        if (hasOverlayPermission && overlayState.songId != null) {
            val intent = Intent(this, SystemOverlayService::class.java)
            startService(intent)
        }
    }
}
