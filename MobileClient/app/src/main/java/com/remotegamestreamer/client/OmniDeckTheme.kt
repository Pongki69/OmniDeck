package com.remotegamestreamer.client

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val OmniDeckColors = darkColorScheme(
    primary = Color(0xFF42D4B1),
    onPrimary = Color(0xFF06251E),
    primaryContainer = Color(0xFF155849),
    onPrimaryContainer = Color(0xFFD8FFF4),
    secondary = Color(0xFFA8CFD2),
    background = Color(0xFF081820),
    onBackground = Color(0xFFF3F6FB),
    surface = Color(0xFF112630),
    onSurface = Color(0xFFF3F6FB),
    surfaceVariant = Color(0xFF1A3440),
    onSurfaceVariant = Color(0xFFBAC5D2),
    outline = Color(0xFF34515C),
    error = Color(0xFFFF8A80),
)

private val OmniDeckShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun OmniDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmniDeckColors,
        shapes = OmniDeckShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
