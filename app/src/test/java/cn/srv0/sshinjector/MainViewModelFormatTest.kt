package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.model.ConnectStage
import cn.srv0.sshinjector.domain.model.HealthStep
import cn.srv0.sshinjector.domain.model.VpnState
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelFormatTest {
    @Test
    fun `formatBytes handles bytes and negatives`() {
        assertEquals("0 B", MainViewModel.formatBytes(0))
        assertEquals("1023 B", MainViewModel.formatBytes(1023))
        assertEquals("-", MainViewModel.formatBytes(-1))
    }

    @Test
    fun `formatBytes handles kb mb gb`() {
        assertEquals("1.0 KB", MainViewModel.formatBytes(1024))
        assertEquals("1.5 KB", MainViewModel.formatBytes(1536))
        assertEquals("1.0 MB", MainViewModel.formatBytes(1024L * 1024))
        assertEquals("1.50 GB", MainViewModel.formatBytes(1024L * 1024 * 1024 + 1024L * 1024 * 1024 / 2))
    }

    @Test
    fun `formatDuration handles seconds and minutes`() {
        assertEquals("00:00:00", MainViewModel.formatDuration(0))
        assertEquals("00:00:59", MainViewModel.formatDuration(59_000))
        assertEquals("00:59:59", MainViewModel.formatDuration(3_599_000))
        assertEquals("01:00:00", MainViewModel.formatDuration(3_600_000))
    }

    @Test
    fun `formatDuration handles days and negatives`() {
        assertEquals("1d 01:01:00", MainViewModel.formatDuration(86_400_000L + 3_660_000L))
        assertEquals("-", MainViewModel.formatDuration(-1))
    }

    // ---- statusDisplay (connectivity-health spec UI 映射; 文案经资源解析, 见 statusStrings) ----

    @Test
    fun `status display shows connected only when verified`() {
        assertEquals(
            "已连接",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connected, verified = true),
                zh,
            ),
        )
        assertEquals(
            "网络验证中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Connected), zh),
        )
    }

    @Test
    fun `status display shows failing step when degraded`() {
        assertEquals(
            "连接异常 · 隧道通道",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connected, failedStep = HealthStep.TUNNEL),
                zh,
            ),
        )
    }

    @Test
    fun `status display attributes connect failure with step`() {
        assertEquals(
            "连接失败 · 身份认证",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Failed, failedStep = HealthStep.AUTH),
                zh,
            ),
        )
        assertEquals(
            "连接失败",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Failed), zh),
        )
        // 连接失败的归因在 controller disconnect (state=Disconnected) 之后补写, 必须仍带步骤
        assertEquals(
            "连接失败 · SSH 连接",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Disconnected, failedStep = HealthStep.SSH),
                zh,
            ),
        )
    }

    @Test
    fun `status display maps transitional states`() {
        assertEquals("未连接", MainViewModel.buildStatusDisplay(VpnState(), zh))
        assertEquals(
            "连接中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Connecting), zh),
        )
        assertEquals(
            "断开中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Disconnecting), zh),
        )
    }

    @Test
    fun `status display shows each connect stage while connecting`() {
        assertEquals(
            "正在加载服务器配置",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.LOAD),
                zh,
            ),
        )
        assertEquals(
            "正在创建 VPN 接口",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.TUN),
                zh,
            ),
        )
        assertEquals(
            "正在连接 SSH",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.TUNNEL),
                zh,
            ),
        )
        assertEquals(
            "正在配置 DNS 拦截",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.DNS),
                zh,
            ),
        )
        assertEquals(
            "正在配置路由规则",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.ROUTES),
                zh,
            ),
        )
    }

    @Test
    fun `status display connect failure takes precedence over stage`() {
        assertEquals(
            "连接失败 · 虚拟网卡",
            MainViewModel.buildStatusDisplay(
                VpnState(
                    status = VpnState.VpnStatus.Connecting,
                    connectStage = ConnectStage.TUN,
                    failedStep = HealthStep.TUN,
                ),
                zh,
            ),
        )
    }

    // ---- 多语言防线: 所有步骤/阶段的 labelRes 必须在资源表内且非空 (硬编码会在此断言失败) ----

    @Test
    fun `every health step and connect stage resolves through resources`() {
        HealthStep.entries.forEach { step ->
            val text = zh(step.labelRes)
            assertTrue("health step ${step.name} label empty", text.isNotBlank())
        }
        ConnectStage.entries.forEach { stage ->
            val text = zh(stage.labelRes)
            assertTrue("connect stage ${stage.name} label empty", text.isNotBlank())
        }
    }

    private companion object {
        /**
         * R.string -> 中文默认文案 (对应 res/values/strings.xml)。单元测试无 Context,
         * 以该表充当 resolver; 新增文案键若未同步, getValue 会直接抛异常暴露。
         */
        val statusStrings: Map<Int, String> =
            mapOf(
                R.string.status_connected to "已连接",
                R.string.status_verifying to "网络验证中",
                R.string.status_connecting to "连接中",
                R.string.status_disconnecting to "断开中",
                R.string.status_failed to "连接失败",
                R.string.status_disconnected to "未连接",
                R.string.status_degraded to "连接异常 · %1\$s",
                R.string.status_failed_step to "连接失败 · %1\$s",
                R.string.server_none to "未连接",
                R.string.connect_stage_load to "正在加载服务器配置",
                R.string.connect_stage_tun to "正在创建 VPN 接口",
                R.string.connect_stage_tunnel to "正在连接 SSH",
                R.string.connect_stage_dns to "正在配置 DNS 拦截",
                R.string.connect_stage_routes to "正在配置路由规则",
                R.string.health_step_ssh to "SSH 连接",
                R.string.health_step_auth to "身份认证",
                R.string.health_step_proxy to "本地代理",
                R.string.health_step_tunnel to "隧道通道",
                R.string.health_step_dns to "DNS 解析",
                R.string.health_step_remote to "远端网络",
                R.string.health_step_tun to "虚拟网卡",
                R.string.health_step_forward to "数据转发",
            )

        val zh: (Int) -> String = { id -> statusStrings.getValue(id) }
    }
}
