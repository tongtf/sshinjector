package cn.srv0.sshinjector.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cn.srv0.sshinjector.R

/**
 * 二级页面统一返回箭头，供 TopAppBar 的 navigationIcon 使用。
 *
 * 9 个页面原本各自复制同一段 IconButton+ArrowBack，此处收敛为唯一实现。
 */
@Composable
fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
    }
}
