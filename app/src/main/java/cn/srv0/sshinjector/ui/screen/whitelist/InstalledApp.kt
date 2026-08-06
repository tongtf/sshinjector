package cn.srv0.sshinjector.ui.screen.whitelist

data class InstalledApp(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
)
