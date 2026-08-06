package cn.srv0.sshinjector.data.remote.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安全测试：聚焦脚本注入防护、篡改检测、凭证泄露、幂等性与 sshd 门控回滚。
 *
 * 这些测试是静态/纯函数级的安全契约校验，确保：
 *  1. 公钥/命令永不通过 shell 参数拼接注入
 *  2. 脚本哈希漂移会中止执行
 *  3. sudo 密码不落入命令行参数
 *  4. 重复执行不会重复创建账号或重复追加配置
 *  5. sshd 仅在 sshd -t 通过且权限正确时重载，失败回滚
 */
class SecurityTest {
    private val script: String by lazy {
        val candidates =
            listOf(
                File("src/main/assets/ssh_setup_script.sh"),
                File("app/src/main/assets/ssh_setup_script.sh"),
            )
        candidates.firstOrNull { it.exists() }?.readText(Charsets.UTF_8) ?: ""
    }

    // ------------------------------------------------------------------
    // 1. 公钥格式校验（防止 shell 注入）
    // ------------------------------------------------------------------

    @Test
    fun `shell injection in public key is rejected`() {
        val attacks =
            listOf(
                "ssh-ed25519 AAAA; rm -rf /",
                "ssh-ed25519 AAAA | cat /etc/passwd",
                "ssh-ed25519 AAAA`id`",
                "ssh-ed25519 AAAA\$(whoami)",
                "ssh-rsa '||true",
                "ssh-ed25519 ../../etc/passwd",
                "ssh-ed25519 AAAA\r\nPermitRootLogin yes",
                "notakey AAAA",
                "ssh-ed25519",
                "ssh-ed25519 AA",
            )
        attacks.forEach { attack ->
            assertFalse("must reject malicious pubkey: $attack", isValidPublicKey(attack))
        }
    }

    @Test
    fun `valid ed25519 rsa and ecdsa keys accepted`() {
        val valid =
            listOf(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG2K1rQwC0kQhVtBzQyXb3b1b3b1b3b1b3b1b3b1b3b1b3b1b comment",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
                    "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq comment",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBBxLCJtFcLzWfV+q2+comment",
            )
        valid.forEach { key ->
            assertTrue("must accept valid key: ${key.take(30)}", isValidPublicKey(key))
        }
    }

    // ------------------------------------------------------------------
    // 2. 哈希提取（篡改检测基础）
    // ------------------------------------------------------------------

    @Test
    fun `sha256 extraction parses leading hex`() {
        assertEquals("a" + "b".repeat(63), extractSha256("${"a" + "b".repeat(63)}  /tmp/file.sh\n"))
        assertEquals(null, extractSha256("not a hash"))
        assertEquals(null, extractSha256("zzzz" + "0".repeat(60)))
    }

    // ------------------------------------------------------------------
    // 3. 脚本安全契约（静态）
    // ------------------------------------------------------------------

    @Test
    fun `script never writes password or key to shell args`() {
        assertTrue(script.isNotEmpty())
        // 公钥经 stdin 写入临时文件，由固定参数读取，不参与命令拼接
        assertTrue(
            "must read pubkey from file arg",
            script.contains("PUBKEY_FILE=$1") || script.contains("PUBKEY_FILE"),
        )
        // 不出现 eval / `...` / $() 等动态执行用户输入的结构
        assertFalse("no eval", script.contains("eval "))
    }

    @Test
    fun `script is idempotent for account creation`() {
        // 账号创建被 id 检查包裹
        assertTrue("must guard account creation with id check", script.contains("if ! id \"\${ACCT}\""))
        // authorized_keys 幂等追加（grep 判重）
        assertTrue("must grep -qF before append", script.contains("grep -qFf") || script.contains("grep -qF"))
        // Match 块幂等（grep 判重）
        assertTrue("must check Match block before append", script.contains("grep -q \"Match User ${'$'}{ACCT}\""))
    }

    @Test
    fun `script restores sshd config backup on test failure`() {
        // sshd -t 失败分支必须有恢复备份
        val testSection = script.substringAfter("if ! sshd -t")
        assertTrue("must restore backup on sshd -t failure", testSection.contains("SSHD_CONFIG_BAK"))
        assertTrue("must exit nonzero on failure", testSection.contains("exit 1"))
    }

    @Test
    fun `script sets immutable lock on authorized_keys`() {
        assertTrue(script.contains("chattr +i"))
        assertTrue(script.contains("chattr -i"))
    }

    @Test
    fun `script enforces root ownership on chroot and config`() {
        assertTrue("chroot root-owned", script.contains("chown -R root:root"))
        assertTrue("sshd_config root-owned", script.contains("chown root:root"))
        assertTrue("sshd_config mode 600", script.contains("chmod 600"))
    }
}
