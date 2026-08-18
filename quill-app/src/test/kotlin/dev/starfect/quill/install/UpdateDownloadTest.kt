package dev.starfect.quill.install

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Fetching a release over real HTTP.
 *
 * Against a local server rather than GitHub, so the test is not a network outage waiting to happen —
 * but real sockets, real status codes and a real redirect, because those are where the fetching goes
 * wrong: GitHub answers the asset URL with a 302 to a storage host, and `HttpURLConnection` will not
 * follow a redirect that changes protocol, which is why the updater follows them itself.
 *
 * The assertion that matters most is the one about a file that does not match its checksum. The
 * updater downloads an archive that becomes the running application; treating a digest as advisory
 * would make the whole publishing of `SHA256SUMS` decorative.
 */
class UpdateDownloadTest {

    @TempDir
    lateinit var root: Path

    private lateinit var server: HttpServer
    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    /** Set by a test to corrupt what the asset endpoint serves. */
    private var served: ByteArray = payload

    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun start() {
        served = payload
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/releases/latest") { it.reply(releaseJson()) }
        // The redirect GitHub actually performs on an asset URL.
        server.createContext("/download/asset") { exchange ->
            exchange.responseHeaders.add("Location", "$base/storage/asset")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/storage/asset") { it.reply(served) }
        server.createContext("/download/SHA256SUMS") { it.reply(checksumsFile()) }
        server.createContext("/gone") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun HttpExchange.reply(body: String) = reply(body.toByteArray(StandardCharsets.UTF_8))

    private fun HttpExchange.reply(body: ByteArray) {
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
        close()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun checksumsFile() = "${sha256(payload)}  Quill-1.2.0-linux-x64.tar.gz\n"

    private fun releaseJson() = """
        {
          "tag_name": "v1.2.0",
          "name": "Quill 1.2.0",
          "draft": false,
          "html_url": "$base/releases/v1.2.0",
          "body": "Notes.",
          "assets": [
            {
              "name": "Quill-1.2.0-linux-x64.tar.gz",
              "size": ${payload.size},
              "browser_download_url": "$base/download/asset"
            },
            {
              "name": "SHA256SUMS",
              "size": 90,
              "browser_download_url": "$base/download/SHA256SUMS"
            }
          ]
        }
    """.trimIndent()

    private fun release() = UpdateService.latestRelease("$base/releases/latest").getOrThrow()

    @Test
    fun `the release is fetched and read over real HTTP`() {
        val release = release()

        assertEquals(Updates.Version(1, 2, 0), release.version)
        assertEquals(2, release.assets.size)
        assertNotNull(release.asset(Updates.CHECKSUMS_ASSET))
    }

    @Test
    fun `a server that is not there is a failed result, not an exception`() {
        // No network, a captive portal, a rate limit: none of them are exceptional, and all of them
        // mean the same thing. An editor that cannot reach the internet is still an editor.
        val result = UpdateService.latestRelease("$base/gone")
        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "404")
    }

    @Test
    fun `an asset is downloaded through its redirect and verified`() {
        val release = release()
        val into = root.resolve("Quill-1.2.0-linux-x64.tar.gz")

        val progress = mutableListOf<UpdateService.Progress>()
        val file = UpdateService.download(release, release.asset("Quill-1.2.0-linux-x64.tar.gz")!!, into) {
            progress += it
        }.getOrThrow()

        assertEquals(into, file)
        assertContentEquals(payload, Files.readAllBytes(file))
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last().bytes)
        assertEquals(1f, progress.last().fraction)
    }

    @Test
    fun `a download that does not match its checksum is discarded`() {
        // The single most important assertion in the updater. This file becomes the application.
        served = payload.copyOf().also { it[42] = (it[42] + 1).toByte() }
        val release = release()
        val into = root.resolve("tampered.tar.gz")

        val result = UpdateService.download(release, release.asset("Quill-1.2.0-linux-x64.tar.gz")!!, into)

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "checksum")
        assertFalse(Files.exists(into), "the bad download was kept, so a retry would pick it up")
    }

    @Test
    fun `a release that publishes no checksums is refused rather than trusted`() {
        // "The publisher forgot" and "somebody is serving something else" look identical from here.
        val release = release()
        val withoutSums = release.copy(assets = release.assets.filterNot { it.name == Updates.CHECKSUMS_ASSET })

        val result = UpdateService.download(withoutSums, withoutSums.assets.single(), root.resolve("x"))

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, Updates.CHECKSUMS_ASSET)
    }

    @Test
    fun `an asset the checksums do not list is refused`() {
        val release = release()
        val unlisted = Updates.Asset("Quill-1.2.0-macos-arm64.dmg", "$base/download/asset", 10)

        val result = UpdateService.download(release, unlisted, root.resolve("y"))

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "does not list")
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "downloaded a different number of bytes")
        assertTrue(expected.contentEquals(actual), "the downloaded bytes differ from what was served")
    }
}
