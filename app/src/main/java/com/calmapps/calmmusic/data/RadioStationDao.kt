package com.calmapps.calmmusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RadioStationDao {

    @Query("SELECT * FROM radio_stations ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllStations(): List<RadioStationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStation(station: RadioStationEntity)

    @Query("DELETE FROM radio_stations WHERE id IN (:ids)")
    suspend fun deleteStations(ids: List<String>)
}
