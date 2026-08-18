package dev.starfect.quill.install

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Unpacking a release archive.
 *
 * The tar reader here is hand-written — Commons Compress is a megabyte and a shrinking
 * configuration to read a format that is 512-byte headers and padding — so the format needs testing
 * against archives `tar` actually produced rather than against ones this could also have written.
 *
 * The executable bit is the reason the Unix releases are tars at all: a zip cannot carry one, and an
 * application image whose launcher arrives without it does not start. That is the assertion that
 * matters most here.
 */
class UpdateServiceTest {

    @TempDir
    lateinit var root: Path

    /** A miniature application image, laid out the way jpackage lays one out on Linux. */
    private fun appImage(name: String = "Quill"): Path {
        val image = root.resolve("source/$name")
        image.resolve("bin").createDirectories()
        image.resolve("lib/app").createDirectories()
        image.resolve("lib/runtime/lib").createDirectories()
        image.resolve("bin/Quill").writeText("#!/bin/sh\nexec java Quill\n")
        image.resolve("bin/Quill").toFile().setExecutable(true, false)
        image.resolve("lib/app/quill-app.jar").writeText("PK not really a jar")
        image.resolve("lib/runtime/lib/modules").writeText("modules")
        return image
    }

    private fun run(vararg command: String): Boolean =
        runCatching {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

    @Test
    fun `a real tar preserves the tree, the contents and the executable launcher`() {
        appImage()
        val archive = root.resolve("Quill.tar.gz")
        assumeTrue(
            run("tar", "-czf", archive.toString(), "-C", root.resolve("source").toString(), "Quill"),
            "tar is not available",
        )

        val image = UpdateService.unpack(archive, root.resolve("unpacked")).getOrThrow()

        assertEquals("Quill", image.fileName.toString())
        assertEquals("#!/bin/sh\nexec java Quill\n", image.resolve("bin/Quill").readText())
        assertEquals("modules", image.resolve("lib/runtime/lib/modules").readText())
        assertTrue(
            image.resolve("bin/Quill").toFile().canExecute(),
            "the launcher arrived without its executable bit, so the update would not start",
        )
    }

    @Test
    fun `a real zip round-trips too`() {
        val image = appImage()
        val archive = root.resolve("Quill.zip")
        assumeTrue(
            run("zip", "-rq", archive.toString(), "Quill", "-x", ".*") ||
                run("sh", "-c", "cd '${image.parent}' && zip -rq '$archive' Quill"),
            "zip is not available",
        )

        val unpacked = UpdateService.unpack(archive, root.resolve("unzipped")).getOrThrow()

        assertEquals("Quill", unpacked.fileName.toString())
        assertEquals("PK not really a jar", unpacked.resolve("lib/app/quill-app.jar").readText())
    }

    @Test
    fun `a long path split across the name and prefix fields is rejoined`() {
        // tar splits a path over 100 characters into a prefix and a name, and an unpacker that
        // reads only the name writes the file to the wrong place — silently, in the root.
        val deep = appImage().resolve(
            "lib/app/" + (1..6).joinToString("/") { "a-reasonably-long-directory-name-$it" },
        )
        deep.createDirectories()
        deep.resolve("buried.txt").writeText("found me")

        val archive = root.resolve("deep.tar.gz")
        assumeTrue(
            run("tar", "-czf", archive.toString(), "-C", root.resolve("source").toString(), "Quill"),
            "tar is not available",
        )

        val image = UpdateService.unpack(archive, root.resolve("deeply")).getOrThrow()
        val restored = image.resolve(root.resolve("source/Quill").relativize(deep.resolve("buried.txt")).toString())
        assertEquals("found me", restored.readText())
    }

    @Test
    fun `an archive holding no directory is refused`() {
        val archive = root.resolve("flat.tar.gz")
        root.resolve("flat").createDirectories()
        root.resolve("flat/loose.txt").writeText("x")
        assumeTrue(
            run("tar", "-czf", archive.toString(), "-C", root.resolve("flat").toString(), "loose.txt"),
            "tar is not available",
        )

        val failure = UpdateService.unpack(archive, root.resolve("out")).exceptionOrNull()
        assertNotNull(failure)
        assertContains(failure.message!!, "no application image")
    }

    @Test
    fun `an entry that escapes the staging directory is refused`() {
        // The archive is checksum-verified before it gets here, so this is not the only line of
        // defence — but "write wherever the archive says" is how an unpacker becomes a way to
        // overwrite a shell profile.
        val archive = root.resolve("escape.tar.gz")
        root.resolve("evil/Quill").createDirectories()
        root.resolve("evil/Quill/ok.txt").writeText("fine")
        assumeTrue(
            run(
                "sh", "-c",
                "cd '${root.resolve("evil")}' && tar -czf '$archive' Quill ../../../etc/hostname 2>/dev/null",
            ),
            "tar refused to build the archive",
        )

        val outside = root.resolve("staging")
        val result = UpdateService.unpack(archive, outside)

        // Either the entry was refused outright, or it was written inside the staging directory —
        // never above it.
        if (result.isSuccess) {
            assertFalse(Files.exists(root.resolve("etc/hostname")), "an entry escaped the staging directory")
        } else {
            assertContains(result.exceptionOrNull()!!.message!!, "outside")
        }
    }
}
