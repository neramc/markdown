package dev.starfect.quill.install

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deciding whether there is a newer Quill, and what could be done about it.
 *
 * Every failure this guards against is silent. A version comparison that reads 1.10.0 as older than
 * 1.9.0 stops offering updates at exactly the point a project starts having them; an asset chosen
 * for the wrong platform downloads eighty megabytes that will not run; replacing an installation
 * the package manager owns leaves dpkg describing files that are no longer there. None of those
 * announce themselves.
 */
class UpdatesTest {

    // ── Versions ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `versions compare by number, not by text`() {
        // The whole reason this is not a string comparison.
        assertTrue(Updates.Version.parse("1.10.0")!! > Updates.Version.parse("1.9.0")!!)
        assertTrue(Updates.Version.parse("2.0.0")!! > Updates.Version.parse("1.99.99")!!)
        assertTrue(Updates.Version.parse("1.2.10")!! > Updates.Version.parse("1.2.9")!!)
        assertEquals(Updates.Version.parse("1.2.0"), Updates.Version.parse("v1.2.0"))
    }

    @Test
    fun `a two-part version is the same as its three-part spelling`() {
        assertEquals(Updates.Version.parse("1.2.0"), Updates.Version.parse("1.2"))
    }

    @Test
    fun `something that is not a version is not read as one`() {
        // "do not know what I am" has to mean "do not offer an update", not "assume zero" — which
        // would make every release look newer to a development build and to a corrupted property.
        assertNull(Updates.Version.parse(null))
        assertNull(Updates.Version.parse(""))
        assertNull(Updates.Version.parse("nightly"))
        assertNull(Updates.Version.parse("release-1.2.0"))
    }

    // ── Reading a release ─────────────────────────────────────────────────────────────────────

    private fun releaseJson(
        tag: String = "v1.2.0",
        draft: Boolean = false,
        assets: List<String> = listOf(
            "Quill-1.2.0-linux-x64.tar.gz",
            "Quill-1.2.0-linux-x64.deb",
            "Quill-1.2.0-linux-x64.rpm",
            "Quill-1.2.0-macos-arm64.dmg",
            "Quill-1.2.0-macos-arm64.tar.gz",
            "Quill-1.2.0-windows-x64.zip",
            "QuillSetup-1.2.0-windows-x64.exe",
            "SHA256SUMS",
        ),
    ) = """
        {
          "tag_name": "$tag",
          "name": "Quill 1.2.0",
          "draft": $draft,
          "prerelease": false,
          "html_url": "https://github.com/neramc/quill/releases/tag/$tag",
          "body": "Notes go here.",
          "assets": [
            ${assets.joinToString(",\n") { """
              {
                "name": "$it",
                "size": 1234,
                "browser_download_url": "https://github.com/neramc/quill/releases/download/$tag/$it"
              }
            """.trimIndent() }}
          ]
        }
    """.trimIndent()

    @Test
    fun `the releases feed reads back into a release`() {
        val release = Updates.parseRelease(releaseJson())!!

        assertEquals(Updates.Version(1, 2, 0), release.version)
        assertEquals("Quill 1.2.0", release.name)
        assertEquals("Notes go here.", release.notes)
        assertEquals(8, release.assets.size)
        assertContains(
            release.asset("SHA256SUMS")!!.url,
            "releases/download/v1.2.0/SHA256SUMS",
        )
    }

    @Test
    fun `a draft is not a release`() {
        // Drafts are visible to the account that created them and to nobody else. Offering one as
        // an update produces a download that 404s for every user who takes it.
        assertNull(Updates.parseRelease(releaseJson(draft = true)))
    }

    @Test
    fun `a feed that is not a release yields nothing rather than half a release`() {
        assertNull(Updates.parseRelease("""{"message":"Not Found"}"""))
        assertNull(Updates.parseRelease("<html>502 Bad Gateway</html>"))
        assertNull(Updates.parseRelease(""))
    }

    // ── Checksums ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `SHA256SUMS is read the way sha256sum writes it`() {
        val text = """
            89d450c79ef950207342b451a8d99ef8a6e2c1551a08d90352a06f19be0e9262  ./Quill-1.2.0-linux-x64.tar.gz
            7af2514affb0c1f768039834de662d8f0c81d08e6e0463f8fd32e15244f55d2c  Quill-1.2.0-windows-x64.zip
            fe8e28b9b1cb1a36d9fc9f10804460cee482b886b5bcd675d3a57e6aba32e7e0 *QuillSetup-1.2.0-windows-x64.exe
        """.trimIndent()

        val sums = Updates.checksums(text)

        // The "./" a glob produces and the "*" that marks binary mode are both dropped: the release
        // lists assets by bare name, and a checksum nobody can look up is a checksum nobody checks.
        assertEquals(3, sums.size)
        assertEquals(
            "89d450c79ef950207342b451a8d99ef8a6e2c1551a08d90352a06f19be0e9262",
            sums["Quill-1.2.0-linux-x64.tar.gz"],
        )
        assertTrue("Quill-1.2.0-windows-x64.zip" in sums)
        assertTrue("QuillSetup-1.2.0-windows-x64.exe" in sums)
    }

    @Test
    fun `lines that are not checksums are ignored rather than misread`() {
        assertEquals(emptyMap(), Updates.checksums("not a checksum file\n\n# a comment\n"))
    }

    // ── This machine ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `each platform maps to the name its release assets use`() {
        assertEquals("linux-x64", Updates.platform("Linux", "amd64"))
        assertEquals("linux-arm64", Updates.platform("Linux", "aarch64"))
        assertEquals("macos-arm64", Updates.platform("Mac OS X", "aarch64"))
        assertEquals("macos-x64", Updates.platform("Mac OS X", "x86_64"))
        assertEquals("windows-x64", Updates.platform("Windows 11", "amd64"))
    }

    @Test
    fun `Windows on ARM is offered the x64 build it already runs`() {
        // There is no arm64 Windows package, because that machine runs the x64 one under emulation.
        assertEquals("windows-x64", Updates.platform("Windows 11", "aarch64"))
    }

    @Test
    fun `a platform no release covers is reported as such`() {
        assertNull(Updates.platform("SunOS", "sparcv9"))
        assertNull(Updates.platform("Linux", "riscv64"))
    }

    // ── What can be done ──────────────────────────────────────────────────────────────────────

    private val release = Updates.parseRelease(releaseJson())!!

    private fun check(
        current: String? = "1.1.0",
        platform: String? = "linux-x64",
        root: Path? = Path.of("/home/somebody/Quill"),
        writable: Boolean = true,
    ) = Updates.check(
        release = release,
        current = current?.let { Updates.Version.parse(it) },
        platform = platform,
        root = root,
        writable = { writable },
    )

    @Test
    fun `the same version is not an update`() {
        assertIs<Updates.Check.UpToDate>(check(current = "1.2.0"))
    }

    @Test
    fun `an older release is not an update`() {
        // A downgrade offered as an upgrade is how a rolled-back release becomes a loop.
        assertIs<Updates.Check.UpToDate>(check(current = "1.3.0"))
    }

    @Test
    fun `a build with no version of its own is never offered an update`() {
        // A development run. Replacing a Gradle-run checkout with a release archive would be
        // startling, and the version it would be "upgrading" from is unknown.
        assertIs<Updates.Check.UpToDate>(check(current = null))
    }

    @Test
    fun `a writable installation is replaced in place, with the archive`() {
        val available = assertIs<Updates.Check.Available>(check())

        assertEquals(Updates.Method.REPLACE, available.method)
        assertEquals("Quill-1.2.0-linux-x64.tar.gz", available.asset.name)
    }

    @Test
    fun `an installation owned by something else is handed its own package instead`() {
        // /opt/quill from a .deb. Writing over it needs root, and doing so would leave dpkg's
        // database describing files that are no longer there.
        val available = assertIs<Updates.Check.Available>(check(writable = false))

        assertEquals(Updates.Method.HAND_OFF, available.method)
        assertEquals("Quill-1.2.0-linux-x64.deb", available.asset.name)
    }

    @Test
    fun `Windows prefers the zip in place and the installer otherwise`() {
        assertEquals(
            "Quill-1.2.0-windows-x64.zip",
            assertIs<Updates.Check.Available>(check(platform = "windows-x64")).asset.name,
        )
        assertEquals(
            "QuillSetup-1.2.0-windows-x64.exe",
            assertIs<Updates.Check.Available>(check(platform = "windows-x64", writable = false)).asset.name,
        )
    }

    @Test
    fun `macOS is handed a dmg rather than having its bundle replaced under it`() {
        assertEquals(
            "Quill-1.2.0-macos-arm64.dmg",
            assertIs<Updates.Check.Available>(check(platform = "macos-arm64", writable = false)).asset.name,
        )
    }

    @Test
    fun `a release with nothing for this platform says so rather than offering the wrong file`() {
        val outcome = assertIs<Updates.Check.CannotApply>(check(platform = "linux-arm64"))
        assertContains(outcome.reason, "linux-arm64")
    }

    @Test
    fun `an unrecognised platform is reported rather than guessed at`() {
        val outcome = assertIs<Updates.Check.CannotApply>(check(platform = null))
        assertContains(outcome.reason, "platform")
    }

    @Test
    fun `a copy running from no application image is handed a package`() {
        // Nothing to replace, so there is nothing to replace it with — but there is still a newer
        // version, and pointing at the file that installs it is a better answer than silence.
        val available = assertIs<Updates.Check.Available>(check(root = null))
        assertEquals(Updates.Method.HAND_OFF, available.method)
    }
}
