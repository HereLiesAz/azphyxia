package com.hereliesaz.illumera.domain

import androidx.compose.runtime.Immutable

@Immutable
data class AddonSubtitle(
    val id: String,
    val url: String,
    val lang: String?,
    val addonName: String
)
