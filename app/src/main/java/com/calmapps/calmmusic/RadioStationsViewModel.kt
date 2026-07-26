package com.calmapps.calmmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.calmapps.calmmusic.data.CalmMusicDatabase
import com.calmapps.calmmusic.data.RadioStationEntity
import com.calmapps.calmmusic.ui.RadioStationUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Dedicated ViewModel for user-added internet radio (Icecast/Shoutcast)
 * stations, following the same pattern as PlaylistsViewModel.
 */
class RadioStationsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app: CalmMusic
        get() = getApplication() as CalmMusic

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    private val radioStationDao by lazy { database.radioStationDao() }

    private val _stations = MutableStateFlow<List<RadioStationUiModel>>(emptyList())
    val stations: StateFlow<List<RadioStationUiModel>> = _stations

    init {
        viewModelScope.launch { refreshStations() }
    }

    suspend fun refreshStations(): List<RadioStationUiModel> {
        return withContext(Dispatchers.IO) {
            val updated = radioStationDao.getAllStations().map {
                RadioStationUiModel(id = it.id, name = it.name, url = it.url)
            }
            _stations.value = updated
            updated
        }
    }

    suspend fun addStation(name: String, url: String) {
        withContext(Dispatchers.IO) {
            radioStationDao.upsertStation(
                RadioStationEntity(id = UUID.randomUUID().toString(), name = name, url = url)
            )
        }
        refreshStations()
    }

    suspend fun updateStation(id: String, name: String, url: String) {
        withContext(Dispatchers.IO) {
            val createdAt = radioStationDao.getAllStations().firstOrNull { it.id == id }?.createdAt
                ?: System.currentTimeMillis()
            radioStationDao.upsertStation(
                RadioStationEntity(id = id, name = name, url = url, createdAt = createdAt)
            )
        }
        refreshStations()
    }

    suspend fun deleteStations(ids: Set<String>) {
        withContext(Dispatchers.IO) {
            radioStationDao.deleteStations(ids.toList())
        }
        refreshStations()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(RadioStationsViewModel::class.java)) {
                        return RadioStationsViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
                }
            }
    }
}
