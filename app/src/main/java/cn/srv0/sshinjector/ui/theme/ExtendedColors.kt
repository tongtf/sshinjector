package cn.srv0.sshinjector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class ExtendedColors(
    val statusOk: Color,
    val statusWarning: Color,
    val statusError: Color,
    val dnsRemoteProxy: Color,
    val dnsLocalDirect: Color,
    val dnsWhitelist: Color,
    val dnsDomainSplit: Color,
)

internal val LightExtendedColors =
    ExtendedColors(
        statusOk = Color(0xFF4CAF50),
        statusWarning = Color(0xFFFF9800),
        statusError = Color(0xFFF44336),
        dnsRemoteProxy = Color(0xFF7C4DFF),
        dnsLocalDirect = Color(0xFF9E9E9E),
        dnsWhitelist = Color(0xFF00BCD4),
        dnsDomainSplit = Color(0xFFFF9800),
    )

internal val DarkExtendedColors =
    ExtendedColors(
        statusOk = Color(0xFF81C784),
        statusWarning = Color(0xFFFFB74D),
        statusError = Color(0xFFE57373),
        dnsRemoteProxy = Color(0xFFB39DDB),
        dnsLocalDirect = Color(0xFFBDBDBD),
        dnsWhitelist = Color(0xFF4DD0E1),
        dnsDomainSplit = Color(0xFFFFB74D),
    )

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    get() = LocalExtendedColors.current
