package cn.srv0.sshinjector.ui.screen.whitelist

data class InstalledApp(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
    /**
     * 应用 uid (`ApplicationInfo.uid`)。
     *
     * 多出口应用选择器用它识别**共享 uid**: 多个包共用一个 uid 时 `getNameForUid` 只会返回其中一个
     * 包名, 按包名写的应用规则会命中整组 —— 用户必须被告知, 否则以为只影响一个 app。
     * -1 = 未知 (测试/未填充)。
     */
    val uid: Int = -1,
)
