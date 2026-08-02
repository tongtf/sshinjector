package com.sshinjector.ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshinjector.domain.vpn.tunnel.TunnelCapability
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin

data class TunnelTypeOption(
    val id: String,
    val name: String,
    val description: String,
)

val TUNNEL_TYPES = listOf(
    TunnelTypeOption("socks5", "SOCKS5 (SSH)", "通过 SSH 隧道的 SOCKS5 代理"),
    TunnelTypeOption("direct", "直连", "不经过代理，直接连接"),
    TunnelTypeOption("https_proxy", "HTTPS Proxy", "通过 HTTP CONNECT 代理"),
    TunnelTypeOption("v2ray", "V2Ray", "V2Ray/VMess 加密隧道"),
    TunnelTypeOption("trojan", "Trojan", "Trojan 加密隧道 (TLS)"),
    TunnelTypeOption("shadowsocks", "Shadowsocks", "Shadowsocks AEAD 加密隧道"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelTypeSelector(
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = TUNNEL_TYPES.find { it.id == selectedType } ?: TUNNEL_TYPES.first()

    Column(modifier = modifier.fillMaxWidth()) {
        Text("隧道模式", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))

        Box {
            OutlinedTextField(
                value = selected.name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            )

            // Transparent clickable overlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                TUNNEL_TYPES.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name, fontWeight = FontWeight.Medium)
                                Text(option.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            onTypeSelected(option.id)
                            expanded = false
                        },
                        trailingIcon = if (option.id == selectedType) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelCapabilityChips(
    capabilities: Set<TunnelCapability>,
    modifier: Modifier = Modifier
) {
    if (capabilities.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        capabilities.forEach { cap ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        when (cap) {
                            TunnelCapability.TCP -> "TCP"
                            TunnelCapability.UDP -> "UDP"
                            TunnelCapability.DNS_OVER_TUNNEL -> "DNS"
                            TunnelCapability.DOMAIN_RESOLVE -> "域名"
                            TunnelCapability.IP_CONNECT -> "IP"
                            TunnelCapability.TLS -> "TLS"
                        },
                        fontSize = 11.sp
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
    }
}

@Composable
fun TunnelConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    numeric: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, modifier = Modifier.width(100.dp))
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
        )
    }
}

@Composable
fun TunnelConfigSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
