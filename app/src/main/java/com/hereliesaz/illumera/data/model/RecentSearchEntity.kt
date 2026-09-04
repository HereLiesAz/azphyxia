package com.hereliesaz.illumera.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity

@Immutable
@Entity(tableName = "recent_searches", primaryKeys = ["profileId", "query"])
data class RecentSearchEntity(
    val profileId: Int,
    val query: String,
    val searchedAt: Long
)
