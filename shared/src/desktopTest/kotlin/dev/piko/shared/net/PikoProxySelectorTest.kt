package dev.piko.shared.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class PikoProxySelectorTest {

    @Test
    fun manualProxyAppliesToRemoteHostsButNeverToLoopback() {
        PikoProxySelector.install(ProxySetting(ProxyMode.MANUAL, ProxyProtocol.SOCKS, "proxy.lan", 1080))
        // OkHttp 建客户端时取的是进程默认值，装上之后必须就是它
        assertEquals(PikoProxySelector, ProxySelector.getDefault())

        val remote = PikoProxySelector.select(URI("https://api-drive.mypikpak.com/drive/v1/files")).single()
        assertEquals(Proxy.Type.SOCKS, remote.type())
        assertEquals(InetSocketAddress.createUnresolved("proxy.lan", 1080), remote.address())
        // 播放器读的本机回环代理不能被送去外面的代理
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(URI("http://127.0.0.1:45123/stream")))

        // 手动但地址没填全：直连，而不是连向一个空地址
        PikoProxySelector.apply(ProxySetting(ProxyMode.MANUAL, ProxyProtocol.HTTP, "", 0))
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(URI("https://api-drive.mypikpak.com/")))

        PikoProxySelector.apply(ProxySetting())
    }
}
