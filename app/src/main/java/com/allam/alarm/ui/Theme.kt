package com.allam.alarm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFE11D48),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E8),
    onPrimaryContainer = Color(0xFF4C0519),
    secondary = Color(0xFF6366F1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF059669),
    background = Color(0xFFFFFBFA),
    surface = Color(0xFFFFFBFA),
    surfaceVariant = Color(0xFFF4ECEE),
    error = Color(0xFFB91C1C),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFB7185),
    onPrimary = Color(0xFF4C0519),
    primaryContainer = Color(0xFF881337),
    onPrimaryContainer = Color(0xFFFFE4E8),
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF34D399),
    background = Color(0xFF16121A),
    surface = Color(0xFF16121A),
    surfaceVariant = Color(0xFF2A2430),
    error = Color(0xFFF87171),
)

@Composable
fun AllamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
