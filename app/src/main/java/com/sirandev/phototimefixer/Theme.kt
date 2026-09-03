/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 SiRan-Dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sirandev.phototimefixer

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 浅色：蓝灰冷色系（非紫），与旧 values/colors.xml 一致 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF526070),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF0E2947),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F4),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFEDEEF3),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

/** 深色：蓝灰冷色系，与旧 values-night/colors.xml 一致 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1D439B),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7C9E4),
    onSecondary = Color(0xFF22364B),
    secondaryContainer = Color(0xFF3A4B62),
    onSecondaryContainer = Color(0xFFD6E4FF),
    error = Color(0xFFF2B8B5),
)

/**
 * 应用主题：Android 12+ 动态取色，低版本回退到上面的蓝灰配色。
 */
@Composable
fun PhotoTimeFixerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}