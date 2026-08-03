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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sshinjector.domain.vpn.tunnel.ConfigField
import com.sshinjector.domain.vpn.tunnel.TunnelCapability
import com.sshinjector.domain.vpn.tunnel.TunnelPlugin

data class TunnelTypeOption(
    val id: String,
    val name: String,
    val description: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelTypeSelector(
    selectedType: String,
    options: List<TunnelTypeOption>,
    onTypeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val effectiveOptions = options.ifEmpty {
        listOf(TunnelTypeOption("socks5", "SOCKS5 (SSH)", ""))
    }
    val selected = effectiveOptions.find { it.id == selectedType } ?: effectiveOptions.first()

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
                effectiveOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name, fontWeight = FontWeight.Medium)
                                if (option.description.isNotBlank()) {
                                    Text(option.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
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

/**
 * 根据插件 configDescriptor 动态渲染配置字段, 读写 values (key -> value 字符串)。
 * 返回 map 由调用方持久化为 tunnelConfigJson。
 */
@Composable
fun TunnelDynamicFields(
    plugin: TunnelPlugin,
    values: Map<String, String>,
    onValuesChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plugin.configDescriptor.fields.forEach { field ->
            val value = values[field.key] ?: field.defaultValue?.toString() ?: ""
            when (field) {
                is ConfigField.TextField -> TunnelConfigField(
                    label = field.label,
                    value = value,
                    onValueChange = { onValuesChange(values + (field.key to it)) },
                    placeholder = field.placeholder,
                    isPassword = field.isPassword
                )
                is ConfigField.NumberField -> TunnelConfigField(
                    label = field.label,
                    value = value,
                    onValueChange = { onValuesChange(values + (field.key to it)) },
                    numeric = true
                )
                is ConfigField.SwitchField -> TunnelConfigSwitch(
                    label = field.label,
                    checked = value.toBooleanStrictOrNull() ?: (field.defaultValue as? Boolean ?: false),
                    onCheckedChange = { onValuesChange(values + (field.key to it.toString())) }
                )
                is ConfigField.DropdownField -> TunnelConfigDropdown(
                    field = field,
                    value = value,
                    onValueChange = { onValuesChange(values + (field.key to it)) }
                )
            }
        }
    }
}

@Composable
fun TunnelConfigDropdown(
    field: ConfigField.DropdownField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = field.options.firstOrNull { it.first == value }?.second ?: field.options.firstOrNull()?.second ?: ""

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = field.label, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                field.options.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onValueChange(key)
                            expanded = false
                        },
                        trailingIcon = if (key == value) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }
    }
}

fun serializeTunnelConfig(values: Map<String, String>): String? {
    if (values.isEmpty()) return null
    return org.json.JSONObject(values).toString()
}

fun parseTunnelConfig(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val obj = org.json.JSONObject(json)
        obj.keys().asSequence().associateWith { obj.optString(it) }
    } catch (_: Exception) {
        emptyMap()
    }
}
