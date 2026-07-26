package com.calmapps.calmmusic.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-added internet radio station (Icecast/Shoutcast stream URL).
 */
@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)
