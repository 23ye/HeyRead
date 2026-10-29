package cn.heyread.app.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/** False while a retained navigation host is covered by the active pane. */
val LocalPaneContentActive = compositionLocalOf { true }

val LocalPaneResizeEpoch = staticCompositionLocalOf { 0 }
