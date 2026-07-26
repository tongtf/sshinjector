package com.sshinjector.data.remote.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.sshinjector.domain.vpn.TunnelChannel
import java.io.InputStream
import java.io.OutputStream

class JschTunnelChannel(
    private val channel: ChannelDirectTCPIP,
    private val cachedInput: InputStream,
    private val cachedOutput: OutputStream,
    private val onClose: (() -> Unit)? = null
) : TunnelChannel {

    override fun connect(timeoutMs: Int): Boolean {
        if (channel.isConnected) return true
        return try {
            channel.connect(timeoutMs)
            channel.isConnected
        } catch (e: Exception) {
            false
        }
    }

    override val inputStream: InputStream?
        get() = cachedInput

    override val outputStream: OutputStream?
        get() = cachedOutput

    override val isConnected: Boolean
        get() = channel.isConnected

    override fun disconnect() {
        try { channel.disconnect() } catch (_: Exception) {}
        onClose?.invoke()
    }
}
