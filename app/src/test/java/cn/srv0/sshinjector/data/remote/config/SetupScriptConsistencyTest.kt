package cn.srv0.sshinjector.data.remote.config

import cn.srv0.sshinjector.domain.model.ServerProvisioning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 校验服务器端配置脚本资产的一致性：
 *  - assets 脚本的 SHA-256 必须与硬编码的 ServerProvisioning.SETUP_SCRIPT_SHA256 一致
 *    （防止脚本被修改后哈希不同步，导致执行前校验失败）。
 *  - 静态安全检查：脚本必须满足安全约束（固定账号、免密策略、门控 reload 等）。
 */
class SetupScriptConsistencyTest {
    private fun readScript(): String {
        val candidates =
            listOf(
                File("src/main/assets/ssh_setup_script.sh"),
                File("app/src/main/assets/ssh_setup_script.sh"),
            )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("assets script missing (tried $candidates, cwd=${System.getProperty("user.dir")})", file)
        return file!!.readText(Charsets.UTF_8)
    }

    @Test
    fun `assets script sha256 matches hardcoded constant`() {
        val content = readScript()
        val actual = sha256Hex(content.toByteArray(Charsets.UTF_8))
        assertEquals(
            "assets 脚本与硬编码 SHA-256 不一致，请同步更新 ServerProvisioning.SETUP_SCRIPT_SHA256",
            ServerProvisioning.SETUP_SCRIPT_SHA256,
            actual,
        )
    }

    @Test
    fun `script uses fixed account name sshproxy`() {
        val script = readScript()
        assertTrue("must hardcode ACCT=sshproxy", script.contains("ACCT=sshproxy"))
        // ACCT 不应从任何外部输入（argv/env）读取
        assertTrue("ACCT must not be parameterized", !script.contains("ACCT=\$1") && !script.contains("ACCT=\""))
    }

    @Test
    fun `script disables password auth and enables pubkey for tunnel account`() {
        val script = readScript()
        assertTrue("must set PasswordAuthentication no", script.contains("PasswordAuthentication no"))
        assertTrue("must set PubkeyAuthentication yes", script.contains("PubkeyAuthentication yes"))
        assertTrue("must enable AllowTcpForwarding", script.contains("AllowTcpForwarding yes"))
        assertTrue("must disable PermitTTY", script.contains("PermitTTY no"))
        assertTrue("must disable X11Forwarding", script.contains("X11Forwarding no"))
    }

    @Test
    fun `script locks password and guards authorized_keys with chattr`() {
        val script = readScript()
        assertTrue("must lock password", script.contains("passwd -l"))
        assertTrue("must chattr -i before write", script.contains("chattr -i"))
        assertTrue("must chattr +i after write", script.contains("chattr +i"))
        assertTrue("must chmod 600 authorized_keys", script.contains("chmod 600"))
    }

    @Test
    fun `script gates sshd reload on sshd -t and backs up config`() {
        val script = readScript()
        assertTrue("must back up sshd_config", script.contains("sshinjector.bak"))
        assertTrue("must validate with sshd -t", script.contains("sshd -t"))
        assertTrue("must restore backup on failure", script.contains("SSHD_CONFIG_BAK"))
        assertTrue("must chown root:root", script.contains("chown root:root"))
        assertTrue("must chmod 600 config", script.contains("chmod 600"))
    }

    @Test
    fun `script sets strict error handling and cleans up temp files`() {
        val script = readScript()
        assertTrue("must set -euo pipefail", script.contains("set -euo pipefail"))
        assertTrue(
            "must clean pubkey temp file",
            script.contains("rm -f"),
        )
    }

    @Test
    fun `script supports dropbear backend without touching global config`() {
        val script = readScript()
        assertTrue("must auto-detect dropbear", script.contains("command -v dropbear"))
        assertTrue("must write dropbear marker", script.contains("sshinjector.configured"))
        assertTrue("must install pubkey for dropbear", script.contains("DROPBEAR_AUTHKEYS"))
        assertFalse("must not modify global dropbear config", script.contains("uci set dropbear"))
    }

    @Test
    fun `script keeps full hardening for openssh backend`() {
        val script = readScript()
        assertTrue("must keep chroot setup", script.contains("ChrootDirectory /home/sshproxy/chroot"))
        assertTrue("must keep Match block", script.contains("Match User sshproxy"))
        assertTrue("must keep immutable lock", script.contains("chattr +i"))
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
