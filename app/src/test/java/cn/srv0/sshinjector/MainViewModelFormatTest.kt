package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.model.ConnectStage
import cn.srv0.sshinjector.domain.model.HealthStep
import cn.srv0.sshinjector.domain.model.VpnState
import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
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

    // ---- statusDisplay (connectivity-health spec UI 映射) ----

    @Test
    fun `status display shows connected only when verified`() {
        assertEquals(
            "已连接",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connected, verified = true),
            ),
        )
        assertEquals(
            "网络验证中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Connected)),
        )
    }

    @Test
    fun `status display shows failing step when degraded`() {
        assertEquals(
            "连接异常 · 隧道通道",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connected, failedStep = HealthStep.TUNNEL),
            ),
        )
    }

    @Test
    fun `status display attributes connect failure with step`() {
        assertEquals(
            "连接失败 · 身份认证",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Failed, failedStep = HealthStep.AUTH),
            ),
        )
        assertEquals(
            "连接失败",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Failed)),
        )
        // 连接失败的归因在 controller disconnect (state=Disconnected) 之后补写, 必须仍带步骤
        assertEquals(
            "连接失败 · SSH 连接",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Disconnected, failedStep = HealthStep.SSH),
            ),
        )
    }

    @Test
    fun `status display maps transitional states`() {
        assertEquals("未连接", MainViewModel.buildStatusDisplay(VpnState()))
        assertEquals(
            "连接中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Connecting)),
        )
        assertEquals(
            "断开中",
            MainViewModel.buildStatusDisplay(VpnState(status = VpnState.VpnStatus.Disconnecting)),
        )
    }

    @Test
    fun `status display shows each connect stage while connecting`() {
        assertEquals(
            "正在加载服务器配置",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.LOAD),
            ),
        )
        assertEquals(
            "正在创建 VPN 接口",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.TUN),
            ),
        )
        assertEquals(
            "正在连接 SSH",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.TUNNEL),
            ),
        )
        assertEquals(
            "正在配置 DNS 拦截",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.DNS),
            ),
        )
        assertEquals(
            "正在配置路由规则",
            MainViewModel.buildStatusDisplay(
                VpnState(status = VpnState.VpnStatus.Connecting, connectStage = ConnectStage.ROUTES),
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
            ),
        )
    }
}
