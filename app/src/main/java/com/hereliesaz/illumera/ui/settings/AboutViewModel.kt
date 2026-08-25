package com.hereliesaz.illumera.ui.settings

import androidx.lifecycle.ViewModel
import com.hereliesaz.illumera.data.update.AppUpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    val updateManager: AppUpdateManager
) : ViewModel()
