package com.sshinjector.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 统一的圆形悬浮图标按钮，用于主界面的“设置”与设置页的“主界面”，
 * 保证两者外观一致。
 */
@Composable
fun FloatingNavIcon(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * 点击防抖：自上次触发起 [intervalMs] (默认 500ms) 内的重复点击只触发一次，
 * 用于确认/跳转按钮防连点。
 */
@Composable
fun rememberClickGuard(intervalMs: Long = 500): (() -> Unit) -> Unit {
    var lastTrigger by remember { mutableStateOf(0L) }
    return { action ->
        val now = System.currentTimeMillis()
        if (now - lastTrigger >= intervalMs) {
            lastTrigger = now
            action()
        }
    }
}