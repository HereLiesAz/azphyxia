package com.hereliesaz.illumera.ui.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True on an actual Android TV device (Leanback launcher), false on phones/tablets —
 * even though this app's manifest makes it installable as either. Several TV-first
 * UI patterns (focus-driven expand/reveal in NavDrawer/TopNavigationBar, the custom
 * on-screen keyboard in Search) need to behave differently when there is no D-pad,
 * since touch produces no focus events to drive them.
 */
@Composable
fun rememberIsTvDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
