package cn.srv0.sshinjector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// 科技商务风 - 深蓝色系 (专业、信任、稳定)

// 浅色主题
private val PrimaryLight = Color(0xFF1565C0) // 深蓝
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val PrimaryContainerLight = Color(0xFFD1E4FF)
private val OnPrimaryContainerLight = Color(0xFF001D36)

private val SecondaryLight = Color(0xFF535F70) // 蓝灰
private val OnSecondaryLight = Color(0xFFFFFFFF)
private val SecondaryContainerLight = Color(0xFFD7E3F7)
private val OnSecondaryContainerLight = Color(0xFF101C2B)

private val TertiaryLight = Color(0xFF6B5778) // 紫灰
private val OnTertiaryLight = Color(0xFFFFFFFF)
private val TertiaryContainerLight = Color(0xFFF2DAFF)
private val OnTertiaryContainerLight = Color(0xFF251431)

private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)

private val BackgroundLight = Color(0xFFF8F9FF) // 浅灰蓝背景
private val OnBackgroundLight = Color(0xFF191C20)
private val SurfaceLight = Color(0xFFF8F9FF)
private val OnSurfaceLight = Color(0xFF191C20)
private val SurfaceVariantLight = Color(0xFFDFE2EB)
private val OnSurfaceVariantLight = Color(0xFF43474E)
private val OutlineLight = Color(0xFF73777F)

// 深色主题
private val PrimaryDark = Color(0xFFA0CAFD) // 亮蓝
private val OnPrimaryDark = Color(0xFF003258)
private val PrimaryContainerDark = Color(0xFF00497D)
private val OnPrimaryContainerDark = Color(0xFFD1E4FF)

private val SecondaryDark = Color(0xFFBBC7DB) // 浅蓝灰
private val OnSecondaryDark = Color(0xFF253140)
private val SecondaryContainerDark = Color(0xFF3B4858)
private val OnSecondaryContainerDark = Color(0xFFD7E3F7)

private val TertiaryDark = Color(0xFFD6BEE4) // 浅紫
private val OnTertiaryDark = Color(0xFF3B2948)
private val TertiaryContainerDark = Color(0xFF523F5F)
private val OnTertiaryContainerDark = Color(0xFFF2DAFF)

private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

private val BackgroundDark = Color(0xFF111318) // 深灰蓝背景
private val OnBackgroundDark = Color(0xFFE1E2E9)
private val SurfaceDark = Color(0xFF111318)
private val OnSurfaceDark = Color(0xFFE1E2E9)
private val SurfaceVariantDark = Color(0xFF43474E)
private val OnSurfaceVariantDark = Color(0xFFC3C7CF)
private val OutlineDark = Color(0xFF8D9199)

private val LightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = OutlineLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        outline = OutlineDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
    )

@Composable
fun SSHInjectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
