package com.friday.wimm.ui.theme

import android.app.Activity
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

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = AppBackgroundDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = Color(0xFF003B9E),
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = AppBackgroundLight,
    onBackground = Color(0xFF1C1B1F),
    surface = CardWhite,
    onSurface = Color(0xFF1C1B1F)

    /* Other default colors to override
    onSecondary = Color.White,
    onTertiary = Color.White,
    onSurfaceVariant = Color(0xFF49454F),
    */
)

@Composable
fun WhereIsMyMoneyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 固定品牌色（#4A7DFF），不使用动态取色，保证 UI 与设计稿一致
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}