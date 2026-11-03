package dev.piko.shared.update

import dev.piko.data.auth.AppLanguage
import java.security.MessageDigest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * 检查更新的来源回退：GitHub API 被限流时改取 release.json，github.com 连不上时经 ghfast.top 下载。
 * 这两条回退只在真出事时才走到，平时的手测碰不上，用假的服务端逐条走一遍。
 */
class ReleaseSourceFallbackTest {
    private val manifestUrl = "https://github.com/NihilDigit/piko/releases/latest/download/release.json"
    private val assetUrl = "https://github.com/NihilDigit/piko/releases/download/v2.0.0/piko-2.0.0-universal.apk"

    private val releaseJson = """
        {"tag_name":"v2.0.0","html_url":"https://github.com/NihilDigit/piko/releases/tag/v2.0.0","body":"",
         "draft":false,"prerelease":false,
         "assets":[{"name":"piko-2.0.0-universal.apk","size":4,"browser_download_url":"$assetUrl","digest":"sha256:ab"}]}
    """.trimIndent()

    @Test
    fun `rate-limited api falls back to the release manifest`(): Unit = runBlocking {
        val requested = mutableListOf<String>()
        val client = client(MockEngine { request ->
            requested += request.url.toString()
            when {
                request.url.host == "api.github.com" -> respondError(HttpStatusCode.Forbidden)
                request.url.toString() == manifestUrl -> respond(releaseJson)
                else -> respondError(HttpStatusCode.NotFound)
            }
        })
        val check = client.check("1.0.0")
        val release = assertIs<ReleaseCheck.Newer>(check).release
        assertEquals("2.0.0", release.version)
        assertEquals("ab", release.asset("piko-2.0.0-universal.apk")?.sha256)
        // 第二个来源成功就停，不再去问 ghfast
        assertEquals(listOf(GithubReleaseClient.LATEST_RELEASE_SOURCES[0], manifestUrl), requested)
    }

    @Test
    fun `every source failing reports a failure, not up to date`(): Unit = runBlocking {
        val client = client(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        assertIs<ReleaseCheck.Failed>(client.check("1.0.0"))
    }

    @Test
    fun `download moves to ghfast when github is unreachable before any byte`(): Unit = runBlocking {
        val client = client(MockEngine { request ->
            if (request.url.host == "ghfast.top") respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4))) else respondError(HttpStatusCode.BadGateway)
        })
        val bytes = ByteArrayOutputStream()
        client.download(asset(), onChunk = { buffer, length -> bytes.write(buffer, 0, length) }, onProgress = {})
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.toByteArray().toList())
    }

    @Test
    fun `download cut off halfway fails instead of appending another mirror`() {
        val client = client(MockEngine { request ->
            if (request.url.host == "ghfast.top") {
                respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4)))
            } else {
                // 交出两个字节后断开：这两个字节已写进调用方的文件，再从镜像接着写就是一份拼接的坏文件
                @OptIn(DelicateCoroutinesApi::class)
                val body = GlobalScope.writer {
                    channel.writeFully(byteArrayOf(1, 2))
                    channel.flush()
                    // 等读的一方先拿走这两个字节：通道带着异常关闭时，还没读走的数据随之丢弃，
                    // 那样就成了一个字节都没收到，测不到中途断开
                    delay(300)
                    error("connection reset")
                }.channel
                respond(body)
            }
        })
        val bytes = ByteArrayOutputStream()
        assertFailsWith<Throwable> {
            runBlocking { client.download(asset(), onChunk = { buffer, length -> bytes.write(buffer, 0, length) }, onProgress = {}) }
        }
        assertEquals(2, bytes.size())
    }

    @Test
    fun `api supplements five languages from verified manifest through mirror`(): Unit = runBlocking {
        checkSupplementaryManifest(tampered = false, unavailable = false)
    }

    @Test
    fun `tampered supplementary manifest does not fail update check`(): Unit = runBlocking {
        checkSupplementaryManifest(tampered = true, unavailable = false)
    }

    @Test
    fun `unavailable supplementary manifest does not fail update check`(): Unit = runBlocking {
        checkSupplementaryManifest(tampered = false, unavailable = true)
    }

    @Test
    fun `manifest without digest is not fetched`(): Unit = runBlocking {
        val requested = mutableListOf<String>()
        val client = client(MockEngine { request ->
            requested += request.url.toString()
            respond("""{"tag_name":"v2.0.0","body":"中文说明","assets":[{"name":"release.json","browser_download_url":"$manifestUrl"}]}""")
        })
        assertEquals("中文说明", assertIs<ReleaseCheck.Newer>(client.check("1.0.0")).release.notes)
        assertEquals(listOf(GithubReleaseClient.LATEST_RELEASE_SOURCES[0]), requested)
    }

    @Test
    fun `direct localized notes skip supplementary manifest`(): Unit = runBlocking {
        val requested = mutableListOf<String>()
        val client = client(MockEngine { request ->
            requested += request.url.toString()
            respond("""{"tag_name":"v2.0.0","body":"中文说明","localized_notes":{"en-US":"English"},"assets":[{"name":"release.json","browser_download_url":"$manifestUrl","digest":"sha256:ab"}]}""")
        })
        assertEquals("English", assertIs<ReleaseCheck.Newer>(client.check("1.0.0")).release.notesFor(AppLanguage.EN))
        assertEquals(listOf(GithubReleaseClient.LATEST_RELEASE_SOURCES[0]), requested)
    }

    private suspend fun checkSupplementaryManifest(tampered: Boolean, unavailable: Boolean) {
        val manifest = """{"tag_name":"v2.0.0","localized_notes":{"zh-Hans":"简体","zh-Hant":"繁體","en":"English","ja":"日本語","ko":"한국어"}}"""
        val content = manifest.encodeToByteArray()
        val digest = if (tampered) "0".repeat(64) else MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val api = """{"tag_name":"v2.0.0","body":"中文说明","assets":[{"name":"release.json","size":${content.size},"browser_download_url":"$manifestUrl","digest":"sha256:$digest"}]}"""
        val requested = mutableListOf<String>()
        val client = client(MockEngine { request ->
            requested += request.url.toString()
            when (request.url.host) {
                "api.github.com" -> respond(api)
                "ghfast.top" -> if (unavailable) respondError(HttpStatusCode.BadGateway) else respond(ByteReadChannel(content))
                else -> respondError(HttpStatusCode.BadGateway)
            }
        })
        val release = assertIs<ReleaseCheck.Newer>(client.check("1.0.0")).release
        assertEquals("中文说明", release.notes)
        assertEquals(listOf(GithubReleaseClient.LATEST_RELEASE_SOURCES[0], manifestUrl, "https://ghfast.top/$manifestUrl"), requested)
        if (tampered || unavailable) {
            assertEquals(emptyMap(), release.localizedNotes)
            assertEquals("中文说明", release.notesFor(AppLanguage.EN))
        } else {
            assertEquals(mapOf(AppLanguage.ZH_CN to "简体", AppLanguage.ZH_HANT to "繁體",
                AppLanguage.EN to "English", AppLanguage.JA to "日本語", AppLanguage.KO to "한국어"), release.localizedNotes)
        }
    }

    private fun client(engine: MockEngine) = GithubReleaseClient(HttpClient(engine), userAgent = "test")

    private fun asset() = ReleaseAsset(name = "piko-2.0.0-universal.apk", url = assetUrl, size = 4, sha256 = null)
}
