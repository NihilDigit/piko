package dev.piko.shared.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PikoProxySelectorTest {

    @AfterTest
    fun resetSelector() {
        PikoProxySelector.systemSelectorOverride = null
        PikoProxySelector.nanoClock = System::nanoTime
        PikoProxySelector.apply(ProxySetting())
    }

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
        // 以 127. 开头的域名不是回环地址，照样走代理
        assertEquals(Proxy.Type.SOCKS, PikoProxySelector.select(URI("https://127.example.com/")).single().type())

        // 手动但地址没填全：直连，而不是连向一个空地址
        PikoProxySelector.apply(ProxySetting(ProxyMode.MANUAL, ProxyProtocol.HTTP, "", 0))
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(URI("https://api-drive.mypikpak.com/")))

        PikoProxySelector.apply(ProxySetting())
    }

    @Test
    fun systemProxyCachesBySchemeHostPortWithTtlAndInvalidatesOnFailureOrModeChange() {
        val proxyAddress = InetSocketAddress.createUnresolved("127.0.0.1", 7890)
        val systemProxy = Proxy(Proxy.Type.HTTP, proxyAddress)
        var selectCalls = 0
        val failures = mutableListOf<Triple<URI, SocketAddress, String>>()
        var nowNanos = 10_000_000_000L

        PikoProxySelector.nanoClock = { nowNanos }
        PikoProxySelector.systemSelectorOverride = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selectCalls++
                return listOf(systemProxy)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                failures += Triple(uri, sa, ioe.message.orEmpty())
            }
        }
        PikoProxySelector.install(ProxySetting(ProxyMode.SYSTEM))

        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("https://api-drive.mypikpak.com/drive/v1/files?page=1")))
        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("https://API-DRIVE.mypikpak.com/drive/v1/about")))
        assertEquals(1, selectCalls)

        // 不同 scheme 或显式端口分开缓存
        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("http://api-drive.mypikpak.com/drive/v1/files")))
        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("https://api-drive.mypikpak.com:8443/drive/v1/files")))
        assertEquals(3, selectCalls)

        // 5s TTL 内命中缓存，到期后重新向系统查询
        nowNanos += 4_999_999_999L
        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("https://api-drive.mypikpak.com/drive/v1/files")))
        assertEquals(3, selectCalls)

        nowNanos += 2L
        assertEquals(listOf(systemProxy), PikoProxySelector.select(URI("https://api-drive.mypikpak.com/drive/v1/files")))
        assertEquals(4, selectCalls)

        // connectFailed 立即驱逐对应键并透传给系统选择器
        val failedUri = URI("https://api-drive.mypikpak.com/drive/v1/files")
        PikoProxySelector.connectFailed(failedUri, proxyAddress, IOException("Connection refused"))
        assertEquals(1, failures.size)
        assertEquals(failedUri, failures.single().first)
        assertEquals(listOf(systemProxy), PikoProxySelector.select(failedUri))
        assertEquals(5, selectCalls)

        // 切换模式清空缓存
        PikoProxySelector.apply(ProxySetting(ProxyMode.NONE))
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(failedUri))
        PikoProxySelector.apply(ProxySetting(ProxyMode.SYSTEM))
        assertEquals(listOf(systemProxy), PikoProxySelector.select(failedUri))
        assertEquals(6, selectCalls)

        // 本机回环在 SYSTEM 模式下同样直连，不进系统代理查询
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(URI("http://127.0.0.1:45123/stream")))
        assertEquals(listOf(Proxy.NO_PROXY), PikoProxySelector.select(URI("http://localhost:8080/health")))
        assertEquals(6, selectCalls)
    }
}
