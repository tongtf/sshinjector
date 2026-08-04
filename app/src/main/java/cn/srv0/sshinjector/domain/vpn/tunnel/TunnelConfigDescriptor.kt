package cn.srv0.sshinjector.domain.vpn.tunnel

data class TunnelConfigDescriptor(
    val fields: List<ConfigField>
)

sealed class ConfigField {
    abstract val key: String
    abstract val label: String
    abstract val required: Boolean
    abstract val defaultValue: Any?

    data class TextField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val placeholder: String = "",
        val isPassword: Boolean = false,
    ) : ConfigField()

    data class NumberField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val min: Int = 0,
        val max: Int = 65535,
    ) : ConfigField()

    data class SwitchField(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val defaultValue: Any? = true,
    ) : ConfigField()

    data class DropdownField(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val defaultValue: Any? = null,
        val options: List<Pair<String, String>>,
    ) : ConfigField()
}
