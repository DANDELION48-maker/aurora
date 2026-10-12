package com.aurora.music.ui.components

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf

// read .value only where the position is drawn so ticks never recompose the shell
val LocalPlaybackPosition = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }
