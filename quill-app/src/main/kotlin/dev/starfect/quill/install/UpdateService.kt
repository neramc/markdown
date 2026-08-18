package dev.starfect.quill.install

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories

/**
 * The side of updating that touches the network and the disk.
 *
 * [Updates] decides *what* should happen and can be tested without either; this does it. The split
 * is not ceremony — every interesting question about updating (is this version newer, which file
 * belongs to this machine, can the installation be replaced) has a wrong answer that is silent, and
 * none of them need a socket to get right.
 *
 * `HttpURLConnection` rather than `java.net.http.HttpClient`, because `HttpClient` lives in the
 * `java.net.http` module and adding it to the jlink module list would put megabytes back into every
 * package to make one request a week. The old API is in `java.base` and is entirely adequate for
 * "GET this, follow redirects, write it to a file".
 */
public object UpdateService {

    /** Identifies Quill to GitHub. The API rejects requests that do not say who is asking. */
    private const val USER_AGENT = "Quill-Updater"

    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000

    /** How far a download has got. */
    public data class Progress(public val bytes: Long, public val total: Long) {
        /** 0..1, or null when the server did not say how big the file is. */
        public val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total) else null
    }

    /**
     * Asks the repository what the newest release is.
     *
     * Every failure — no network, a captive portal, GitHub being down, a rate limit — comes back as
     * a failed [Result] rather than an exception, because none of them are exceptional and all of
     * them mean the same thing to the caller: it could not find out. An editor that cannot reach
     * the internet is still an editor.
     */
    public fun latestRelease(url: String = Updates.LATEST_RELEASE_URL): Result<Updates.Release> =
        runCatching {
            val json = get(url) { it.readBytes().toString(StandardCharsets.UTF_8) }
            Updates.parseRelease(json) ?: error("The releases feed was not in the expected shape.")
        }

    /**
     * Downloads [asset] to [into], verifying it against the release's `SHA256SUMS`.
     *
     * The checksum is not optional and it is not advisory. This writes an executable — an installer,
     * or an archive that becomes the running application — fetched over a network, and the whole
     * reason a release publishes digests is so that the thing consuming them checks. A file that
     * does not match is deleted rather than kept, so a retry cannot pick up the bad copy.
     *
     * A release with no `SHA256SUMS` is refused for the same reason. "The publisher forgot" and
     * "somebody is serving something else" look identical from here.
     */
    public fun download(
        release: Updates.Release,
        asset: Updates.Asset,
        into: Path,
        onProgress: (Progress) -> Unit = {},
    ): Result<Path> = runCatching {
        val checksums = release.asset(Updates.CHECKSUMS_ASSET)
            ?: error("Release ${release.version} publishes no ${Updates.CHECKSUMS_ASSET}, so the " +
                "download cannot be verified.")
        val expected = Updates.checksums(get(checksums.url) { it.readBytes().toString(StandardCharsets.UTF_8) })[asset.name]
            ?: error("${Updates.CHECKSUMS_ASSET} does not list ${asset.name}.")

        into.parent?.createDirectories()
        val digest = MessageDigest.getInstance("SHA-256")

        get(asset.url) { stream ->
            val total = if (asset.size > 0) asset.size else -1
            DigestInputStream(stream, digest).use { digested ->
                Files.newOutputStream(into).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var copied = 0L
                    while (true) {
                        val read = digested.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        onProgress(Progress(copied, total))
                    }
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            Files.deleteIfExists(into)
            error("${asset.name} did not match its published checksum and was discarded.")
        }
        into
    }

    /** GETs [url], following redirects, and hands the body to [read]. */
    private fun <T> get(url: String, read: (InputStream) -> T): T {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream, application/json")
            // Redirects are followed by hand because HttpURLConnection refuses to follow one that
            // changes protocol, and GitHub's asset URLs redirect from github.com to an S3 host.
            connection.instanceFollowRedirects = false

            when (val status = connection.responseCode) {
                in 200..299 -> return connection.inputStream.use(read)
                in 300..399 -> {
                    current = connection.getHeaderField("Location")
                        ?: error("The server redirected without saying where.")
                    connection.disconnect()
                }
                else -> {
                    connection.disconnect()
                    error("The server answered $status.")
                }
            }
        }
        error("Too many redirects.")
    }

    private const val MAX_REDIRECTS = 5

    /**
     * Unpacks a release archive into [into] and returns the application image inside it.
     *
     * The archives hold one top-level directory — `Quill/` on Linux and Windows, `Quill.app/` on
     * macOS — so what comes back is that directory rather than the staging area, which is what the
     * swap needs to rename into place.
     */
    public fun unpack(archive: Path, into: Path): Result<Path> = runCatching {
        into.createDirectories()
        if (archive.fileName.toString().endsWith(".zip")) unpackZip(archive, into) else unpackTarGz(archive, into)

        Files.list(into).use { entries ->
            entries.filter(Files::isDirectory).findFirst()
                .orElseThrow { IllegalStateException("The archive held no application image.") }
        }
    }

    private fun unpackZip(archive: Path, into: Path) {
        ZipInputStream(Files.newInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = resolveWithin(into, entry.name)
                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent?.createDirectories()
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * Unpacks a gzipped tar.
     *
     * Hand-rolled rather than pulled in. Apache Commons Compress is a megabyte and a ProGuard
     * configuration to read a format that is five hundred and twelve byte headers and padding, and
     * the one part that is genuinely subtle — the executable bit on the launcher, which a zip
     * cannot carry and which is why the Unix archives are tars at all — a library would not save.
     */
    private fun unpackTarGz(archive: Path, into: Path) {
        GZIPInputStream(Files.newInputStream(archive).buffered()).use { stream ->
            val header = ByteArray(TAR_BLOCK)
            // Set by a preceding GNU long-name entry, consumed by the entry after it.
            var pendingName: String? = null

            while (true) {
                if (!stream.readFully(header)) break
                if (header.all { it.toInt() == 0 }) break // two zero blocks end the archive

                val name = header.string(0, 100)
                if (name.isEmpty() && pendingName == null) break
                val mode = header.octal(100, 8)
                val size = header.octal(124, 12)
                val type = header[156].toInt().toChar()

                if (type == GNU_LONG_NAME) {
                    // GNU tar's answer to a path too long for the 100-byte name field: an entry
                    // whose *body* is the real name of the entry that follows. Skipping it -- which
                    // is what the obvious reader does, since it looks like metadata -- leaves the
                    // next header's truncated name, and writes the file to a path that is a prefix
                    // of where it belongs. Every release archive hits this: the JDK ships plenty of
                    // resources whose paths run past a hundred characters.
                    val bytes = ByteArray(size.toInt())
                    if (!stream.readFully(bytes)) break
                    pendingName = String(bytes, StandardCharsets.UTF_8).trimEnd(NUL).trim()
                    stream.skipExactly(padding(size))
                    continue
                }

                // ustar's answer to the same problem: the path split over two fields.
                val prefix = header.string(345, 155)
                val full = pendingName ?: if (prefix.isEmpty()) name else "$prefix/$name"
                pendingName = null

                val target = resolveWithin(into, full)
                when (type) {
                    '5' -> target.createDirectories()
                    // '0' is a regular file; NUL is what tar wrote before it had type flags, and
                    // is still what some producers emit.
                    '0', NUL -> {
                        target.parent?.createDirectories()
                        Files.newOutputStream(target).use { out ->
                            stream.copyExactly(out, size)
                        }
                        // The launcher and every binary in the bundled runtime have to stay
                        // executable, and this header is the only place that fact exists.
                        if (mode and OWNER_EXECUTE != 0L) target.toFile().setExecutable(true, false)
                    }
                    // Links, long link names and everything else: skip the body and carry on
                    // rather than failing. A release archive contains none of them.
                    else -> stream.skipExactly(size)
                }
                stream.skipExactly(padding(size))
            }
        }
    }

    /** GNU tar's type flag for "the name of the next entry is in my body". */
    private const val GNU_LONG_NAME = 'L'

    /** The type flag pre-POSIX tar used for a regular file. */
    private const val NUL = '\u0000'

    /** `0o100` in the header's mode field. */
    private const val OWNER_EXECUTE = 0b001_000_000L

    private const val TAR_BLOCK = 512

    private fun padding(size: Long) = (TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK

    private fun InputStream.readFully(into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val count = read(into, read, into.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun InputStream.copyExactly(out: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = bytes
        while (left > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) error("The archive ended in the middle of a file.")
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun InputStream.skipExactly(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it].toInt() == 0 } ?: (offset + length)
        return String(this, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long =
        string(offset, length).takeIf { it.isNotEmpty() }?.toLongOrNull(8) ?: 0L

    /**
     * Resolves an archive entry inside [root], refusing anything that escapes it.
     *
     * The archive is verified against a published checksum before it gets here, so this is not the
     * only line of defence — but "write wherever the archive says" is how an unpacker becomes a way
     * to overwrite a shell profile, and the check costs nothing.
     */
    private fun resolveWithin(root: Path, entry: String): Path {
        val resolved = root.resolve(entry.replace('\\', '/')).normalize()
        require(resolved.startsWith(root.normalize())) { "the archive contains a path outside itself: $entry" }
        return resolved
    }
}
