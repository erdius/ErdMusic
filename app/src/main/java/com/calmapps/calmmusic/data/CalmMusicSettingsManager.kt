package com.calmapps.calmmusic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One entry of the bottom navigation configuration: which tab (by route) and
 * whether it is currently shown. Order in the list is the display order.
 */
data class TabSetting(
    val route: String,
    val visible: Boolean,
)

class CalmMusicSettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _includeLocalMusic = MutableStateFlow(getIncludeLocalMusicSync())
    val includeLocalMusic: StateFlow<Boolean> = _includeLocalMusic.asStateFlow()

    private val _localMusicFolders = MutableStateFlow(getLocalMusicFoldersSync())
    val localMusicFolders: StateFlow<Set<String>> = _localMusicFolders.asStateFlow()

    private val _tabSettings = MutableStateFlow(getTabSettingsSync())
    val tabSettings: StateFlow<List<TabSetting>> = _tabSettings.asStateFlow()

    fun getTabSettingsSync(): List<TabSetting> {
        val raw = prefs.getString(KEY_TAB_SETTINGS, null)
        val parsed = raw?.split(',')?.mapNotNull { part ->
            val pieces = part.split(':')
            if (pieces.size != 2) return@mapNotNull null
            val route = pieces[0].trim()
            if (route.isEmpty()) return@mapNotNull null
            TabSetting(route = route, visible = pieces[1].trim() == "1")
        } ?: emptyList()
        return normalizeTabSettings(parsed)
    }

    fun setTabSettings(settings: List<TabSetting>) {
        val normalized = normalizeTabSettings(settings)
        val serialized = normalized.joinToString(",") { setting ->
            "${setting.route}:${if (setting.visible) 1 else 0}"
        }
        prefs.edit { putString(KEY_TAB_SETTINGS, serialized) }
        _tabSettings.value = normalized
    }

    /**
     * Keep stored tab config valid: drop unknown routes, append any tabs the
     * app has gained since the config was saved, and never allow the More tab
     * to be hidden (it is the only path to Settings).
     */
    private fun normalizeTabSettings(settings: List<TabSetting>): List<TabSetting> {
        // The Streams tab was renamed to Radio; remap any config saved under
        // the old route so a user's existing order/visibility carries over.
        val remapped = settings.map { setting ->
            if (setting.route == "streams") setting.copy(route = "radio") else setting
        }
        val known = remapped.filter { it.route in DEFAULT_TAB_ROUTES }.distinctBy { it.route }
        val missing = DEFAULT_TAB_ROUTES
            .filter { route -> known.none { it.route == route } }
            .map { TabSetting(route = it, visible = true) }
        return (known + missing).map { setting ->
            if (setting.route == TAB_ROUTE_MORE) setting.copy(visible = true) else setting
        }
    }

    fun getLastLocalLibraryScanMillis(): Long {
        return prefs.getLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, 0L)
    }

    fun updateLastLocalLibraryScanMillis(value: Long) {
        prefs.edit { putLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, value) }
    }

    fun setIncludeLocalMusic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_LOCAL_MUSIC, enabled) }
        _includeLocalMusic.value = enabled
    }

    private fun getIncludeLocalMusicSync(): Boolean {
        return prefs.getBoolean(KEY_INCLUDE_LOCAL_MUSIC, false)
    }

    fun addLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.add(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun removeLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.remove(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun getLocalMusicFoldersSync(): Set<String> {
        return prefs.getStringSet(KEY_LOCAL_MUSIC_FOLDERS, emptySet()) ?: emptySet()
    }

    // Permissions onboarding
    fun hasCompletedPermissionsOnboarding(): Boolean {
        return prefs.getBoolean(KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING, false)
    }

    fun setHasCompletedPermissionsOnboarding(completed: Boolean) {
        prefs.edit { putBoolean(KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING, completed) }
    }

    companion object {
        private const val PREFS_NAME = "calmmusic_settings"
        private const val KEY_INCLUDE_LOCAL_MUSIC = "include_local_music"
        private const val KEY_LOCAL_MUSIC_FOLDERS = "local_music_folders"
        private const val KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS = "last_local_library_scan_millis"
        private const val KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING = "has_completed_permissions_onboarding"
        private const val KEY_TAB_SETTINGS = "bottom_tab_settings"

        const val TAB_ROUTE_MORE = "more"

        /** Canonical bottom-tab routes in default display order. */
        val DEFAULT_TAB_ROUTES = listOf("playlists", "artists", "songs", "albums", "radio", TAB_ROUTE_MORE)
    }
}
