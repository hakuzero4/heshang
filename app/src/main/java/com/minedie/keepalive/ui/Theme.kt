package com.minedie.keepalive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val PageBg = Color(0xFFF3F4F6)
internal val Ink = Color(0xFF1C1C1E)
internal val Muted = Color(0xFF8E8E93)
internal val Green = Color(0xFF34C759)
internal val GreenCircle = Color(0xFF3DDC84)
internal val Blue = Color(0xFF007AFF)
internal val Red = Color(0xFFFF3B30)
internal val Purple = Color(0xFF7B61FF)
internal val DotGray = Color(0xFFC7C7CC)
internal val TrackOff = Color(0xFFE5E5EA)
internal val Amber = Color(0xFFB86E00)

@Composable
fun KeepAliveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            background = PageBg,
            surface = Color.White,
        ),
        content = content,
    )
}
