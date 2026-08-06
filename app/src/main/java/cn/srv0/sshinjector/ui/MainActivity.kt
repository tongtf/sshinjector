package cn.srv0.sshinjector.ui

import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.srv0.sshinjector.R
import cn.srv0.sshinjector.ui.component.FloatingNavIcon
import cn.srv0.sshinjector.ui.component.rememberClickGuard
import cn.srv0.sshinjector.ui.locale.LocaleManager
import cn.srv0.sshinjector.ui.navigation.AppNavGraph
import cn.srv0.sshinjector.ui.theme.SSHInjectorTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private fun applySavedLanguage() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val lang = prefs.getString("language", LocaleManager.LANGUAGE_SYSTEM) ?: LocaleManager.LANGUAGE_SYSTEM
        val locale =
            when (lang) {
                LocaleManager.LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                LocaleManager.LANGUAGE_ENGLISH -> Locale.ENGLISH
                LocaleManager.LANGUAGE_RUSSIAN -> Locale("ru")
                else -> return
            }
        val config = resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)
        setContent {
            SSHInjectorTheme { MainScreen() }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val navIcon: ImageVector = if (currentRoute == "settings") Icons.Filled.Home else Icons.Filled.Settings
    val navDesc: String =
        if (currentRoute == "settings") {
            stringResource(R.string.nav_main)
        } else {
            stringResource(R.string.nav_settings)
        }
    val guard = rememberClickGuard()

    var iconOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var screenBounds by remember { mutableStateOf(IntSize.Zero) }
    val buttonSizePx = with(LocalDensity.current) { 56.dp.roundToPx() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { screenBounds = it },
    ) {
        AppNavGraph(
            navController = navController,
            modifier = Modifier.fillMaxSize(),
        )
        DraggableNavIcon(
            imageVector = navIcon,
            contentDescription = navDesc,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 24.dp, end = 16.dp)
                    .offset {
                        IntOffset(
                            iconOffset.x.roundToInt().coerceIn(
                                -(screenBounds.width - buttonSizePx),
                                0,
                            ),
                            iconOffset.y.roundToInt().coerceIn(
                                -(screenBounds.height - buttonSizePx),
                                0,
                            ),
                        )
                    },
            onDragDelta = { delta ->
                iconOffset =
                    Offset(
                        (iconOffset.x + delta.x).coerceIn(
                            -(screenBounds.width - buttonSizePx).toFloat(),
                            0f,
                        ),
                        (iconOffset.y + delta.y).coerceIn(
                            -(screenBounds.height - buttonSizePx).toFloat(),
                            0f,
                        ),
                    )
            },
            onClick = {
                guard {
                    if (navController.currentDestination?.route == "settings") {
                        navController.navigate("dashboard") {
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate("settings")
                    }
                }
            },
        )
    }
}

@Composable
fun DraggableNavIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onDragDelta: (Offset) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectTapGestures { onClick() }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount)
                    }
                },
    ) {
        FloatingNavIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
        )
    }
}
