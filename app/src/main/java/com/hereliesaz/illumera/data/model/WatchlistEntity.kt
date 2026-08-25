package com.hereliesaz.illumera.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val id: String,       // IMDb or addon ID (e.g., "tt0111161")
    val type: String,                 // "movie" or "series"
    val title: String,
    val poster: String?,
    val addedAt: Long                 // System.currentTimeMillis() when bookmarked
)
