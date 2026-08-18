package dev.starfect.quill.install

import dev.starfect.quill.io.vscode.Jsonc
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finding out whether there is a newer Quill, and deciding what can be done about it.
 *
 * Everything here is a pure function of text: the releases JSON, the `SHA256SUMS` file, the version
 * string the launcher was stamped with, and the shape of the directory Quill is running from. The
 * network and the filesystem live in [UpdateService], so the parts with interesting answers — which
 * asset belongs to this machine, whether 1.10.0 is newer than 1.9.0, whether replacing the
 * installation in place is even possible — can be tested without either.
 *
 * ## What "update" means depends on how Quill got here
 *
 * An application image is a directory. Updating one is replacing that directory, which works when
 * it is somewhere the user can write — a portable unpack, or the per-user install the Windows
 * installer makes under `%LOCALAPPDATA%\Programs`. It does not work for `/opt/quill` from a `.deb`,
 * which belongs to the package manager and needs root; taking that over would leave dpkg's database
 * describing files that are no longer there.
 *
 * So there are two outcomes and the difference is stated rather than hidden: replace it, or fetch
 * the package this machine installs and hand it to the thing that installs packages.
 */
public object Updates {

    /** Where releases are published. The application is the client of its own repository. */
    public const val LATEST_RELEASE_URL: String =
        "https://api.github.com/repos/neramc/quill/releases/latest"

    /** The file every release carries, listing a SHA-256 for each asset beside it. */
    public const val CHECKSUMS_ASSET: String = "SHA256SUMS"

    // ── Versions ──────────────────────────────────────────────────────────────────────────────

    /**
     * A release version.
     *
     * Compared field by field rather than as text, because the string comparison that looks like it
     * works says 1.10.0 is older than 1.9.0 and then stops offering updates at exactly the point a
     * project starts having them.
     */
    public data class Version(
        public val major: Int,
        public val minor: Int,
        public val patch: Int,
    ) : Comparable<Version> {

        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString(): String = "$major.$minor.$patch"

        public companion object {
            private val PATTERN = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?""")

            /**
             * Reads a version out of [text], or null when there is none.
             *
             * Tolerant at the edges on purpose: tags are written `v1.2.0`, the launcher is stamped
             * `1.2.0`, and a pre-release suffix is not something to fail over. Anything genuinely
             * unparseable returns null, and the caller treats "I do not know what I am" as "do not
             * offer an update" rather than guessing.
             */
            public fun parse(text: String?): Version? {
                val match = PATTERN.find(text?.trim().orEmpty()) ?: return null
                if (match.range.first != 0) return null
                return Version(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toIntOrNull() ?: 0,
                )
            }
        }
    }

    /** The version this copy was built as, or null for a development run that was never stamped. */
    public val running: Version? get() = Version.parse(System.getProperty("quill.version"))

    // ── Releases ──────────────────────────────────────────────────────────────────────────────

    /** One downloadable file in a release. */
    public data class Asset(
        public val name: String,
        public val url: String,
        public val size: Long,
    )

    /** A published release, as much of it as matters here. */
    public data class Release(
        public val version: Version,
        public val name: String,
        public val notes: String,
        public val pageUrl: String,
        public val assets: List<Asset>,
    ) {
        public fun asset(name: String): Asset? = assets.firstOrNull { it.name == name }
    }

    /** Reads the releases API's answer. Returns null for anything that is not one. */
    public fun parseRelease(json: String): Release? {
        val root = Jsonc.parseOrNull(json) as? Map<*, *> ?: return null
        if (root["draft"] == true) return null

        val version = Version.parse(root["tag_name"] as? String) ?: return null
        val assets = (root["assets"] as? List<*>).orEmpty().mapNotNull { entry ->
            val asset = entry as? Map<*, *> ?: return@mapNotNull null
            val name = asset["name"] as? String ?: return@mapNotNull null
            val url = asset["browser_download_url"] as? String ?: return@mapNotNull null
            Asset(name, url, (asset["size"] as? Double)?.toLong() ?: 0L)
        }

        return Release(
            version = version,
            name = root["name"] as? String ?: "Quill $version",
            notes = root["body"] as? String ?: "",
            pageUrl = root["html_url"] as? String ?: "",
            assets = assets,
        )
    }

    /**
     * Reads `SHA256SUMS`, which is `sha256sum` output: a digest, two spaces, a file name.
     *
     * The leading `./` that `sha256sum` writes when it is given a glob is dropped, because the
     * release lists its assets by bare name and a checksum keyed `./Quill-1.2.0-linux-x64.tar.gz`
     * matches nothing. A leading `*`, which marks a file read in binary mode, is dropped too.
     */
    public fun checksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val match = Regex("""([0-9a-fA-F]{64})\s+\*?(?:\./)?(.+)""").matchEntire(line.trim())
                ?: return@mapNotNull null
            match.groupValues[2] to match.groupValues[1].lowercase()
        }
        .toMap()

    // ── This machine ──────────────────────────────────────────────────────────────────────────

    /**
     * The platform string releases are named with, or null for a machine no release covers.
     *
     * Windows on ARM deliberately maps to `windows-x64`: it runs the x64 build under emulation, and
     * there is no arm64 Windows package to offer instead.
     */
    public fun platform(
        os: String = System.getProperty("os.name").orEmpty(),
        arch: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        val name = os.lowercase()
        val architecture = when (arch.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> return null
        }
        return when {
            "win" in name -> "windows-x64"
            "mac" in name || "darwin" in name -> "macos-$architecture"
            "linux" in name -> "linux-$architecture"
            else -> null
        }
    }

    /**
     * The application image this process is running from, or null when it is not running from one.
     *
     * Found by looking for the bundled runtime rather than by assuming a layout: jpackage puts the
     * launcher at `bin/` on Linux, at the root on Windows and inside `Contents/MacOS` on macOS, and
     * the jars move with it. The one thing every layout has is a `runtime` directory somewhere
     * under the root, so the root is the nearest ancestor that owns one.
     */
    public fun appImageRoot(from: Path? = codeSourceDirectory()): Path? {
        var candidate = from?.toAbsolutePath()?.normalize()
        var levels = 0
        while (candidate != null && levels < MAX_ANCESTORS) {
            if (Files.isDirectory(candidate.resolve("runtime")) ||
                Files.isDirectory(candidate.resolve("lib/runtime")) ||
                Files.isDirectory(candidate.resolve("Contents/runtime"))
            ) {
                return candidate
            }
            candidate = candidate.parent
            levels++
        }
        return null
    }

    private const val MAX_ANCESTORS = 6

    private fun codeSourceDirectory(): Path? = runCatching {
        val location = Updates::class.java.protectionDomain?.codeSource?.location ?: return null
        val path = Path.of(location.toURI())
        if (Files.isDirectory(path)) path else path.parent
    }.getOrNull()

    // ── What can be done ──────────────────────────────────────────────────────────────────────

    /** How an update would be applied. */
    public enum class Method {
        /**
         * Unpack the new image beside the old one and swap them.
         *
         * Only when the installation and its parent are both writable, which covers a portable
         * unpack and the per-user installation the Windows installer makes. Everything stays where
         * it was, so shortcuts, associations and the Apps & features entry keep pointing at it.
         */
        REPLACE,

        /**
         * Download the package this platform installs and open it.
         *
         * For an installation owned by something else — `/opt/quill` from a `.deb`, an `.app` in
         * `/Applications` — where writing over it would either need root or leave the package
         * manager describing files that are no longer there.
         */
        HAND_OFF,
    }

    /** What checking found. */
    public sealed interface Check {
        /** Nothing newer is published. */
        public data object UpToDate : Check

        /** There is a newer release, and this is how it would be applied. */
        public data class Available(
            public val release: Release,
            public val asset: Asset,
            public val method: Method,
        ) : Check

        /** There is a newer release but nothing here can act on it, and why. */
        public data class CannotApply(
            public val release: Release,
            public val reason: String,
        ) : Check
    }

    /**
     * Decides what to do about [release], given where Quill is installed.
     *
     * [writable] is passed in rather than probed so the decision is testable: whether a directory
     * can be written to is a fact about a machine, and the interesting logic is what follows from
     * it.
     */
    public fun check(
        release: Release,
        current: Version? = running,
        platform: String? = platform(),
        root: Path? = appImageRoot(),
        writable: (Path) -> Boolean = { Files.isWritable(it) },
    ): Check {
        if (current == null || release.version <= current) return Check.UpToDate

        if (platform == null) {
            return Check.CannotApply(release, "No Quill release is published for this platform.")
        }

        // Replacing needs to write both the image and the directory holding it — the swap renames
        // one sibling over another, and a writable image inside a read-only parent cannot do that.
        val replaceable = root != null && writable(root) && root.parent?.let(writable) == true

        return if (replaceable) {
            val portable = portableAsset(release, platform)
                ?: return Check.CannotApply(
                    release,
                    "Release ${release.version} has no archive for $platform.",
                )
            Check.Available(release, portable, Method.REPLACE)
        } else {
            val package_ = installerAsset(release, platform)
                ?: return Check.CannotApply(
                    release,
                    "Release ${release.version} has no installer for $platform.",
                )
            Check.Available(release, package_, Method.HAND_OFF)
        }
    }

    /** The archive that *is* the application image: what a replacement unpacks. */
    internal fun portableAsset(release: Release, platform: String): Asset? =
        release.asset("Quill-${release.version}-$platform.zip")
            ?: release.asset("Quill-${release.version}-$platform.tar.gz")

    /**
     * The package this platform installs, in the order somebody on it would want.
     *
     * Debian before RPM on Linux is a coin toss that has to land somewhere; the hand-off opens the
     * file with the desktop's own handler, which on an RPM distribution will decline a `.deb` and
     * say so — better than this guessing wrong silently and better than offering a choice nobody
     * has the information to make.
     */
    internal fun installerAsset(release: Release, platform: String): Asset? = when {
        platform.startsWith("windows") -> release.asset("QuillSetup-${release.version}-$platform.exe")
        platform.startsWith("macos") -> release.asset("Quill-${release.version}-$platform.dmg")
        else -> release.asset("Quill-${release.version}-$platform.deb")
            ?: release.asset("Quill-${release.version}-$platform.rpm")
    } ?: portableAsset(release, platform)
}
