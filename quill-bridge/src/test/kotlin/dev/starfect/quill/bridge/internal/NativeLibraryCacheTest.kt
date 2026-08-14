package dev.starfect.quill.bridge.internal
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library reaches the disk once, not once per launch.
 *
 * Tested against a jar built here rather than against the real library, for two reasons: the real
 * one is on the filesystem during a Gradle test run — so it takes the "already a file" path and
 * never exercises the cache at all — and the thing under test is the caching decision, which has
 * nothing to do with whether the bytes are a valid shared object.
 */
class NativeLibraryCacheTest {

    private val temporary = Files.createTempDirectory("quill-native-cache-test")

    @AfterTest
    fun cleanUp() {
        System.clearProperty(NativeLibraryLoader.CACHE_PROPERTY)
        temporary.toFile().deleteRecursively()
    }

    private fun jarContaining(entry: String, bytes: ByteArray, name: String): URL {
        val jar = temporary.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(entry))
            out.write(bytes)
            out.closeEntry()
        }
        return jar.toUri().toURL()
    }

    private fun cacheRoot(): Path = temporary.resolve("cache").also {
        System.setProperty(NativeLibraryLoader.CACHE_PROPERTY, it.toString())
    }

    private fun extractedFiles(root: Path): List<Path> =
        if (!Files.isDirectory(root)) {
            emptyList()
        } else {
            Files.walk(root).use { walk -> walk.filter(Files::isRegularFile).toList() }
        }

    @Test
    fun `a library inside a jar is extracted once and reused`() {
        val root = cacheRoot()
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val jar = jarContaining("native/linux-x64/libquill_core.so", payload, "one.jar")

        URLClassLoader(arrayOf(jar), null).use { loader ->
            val first = NativeLibraryLoader.resolve(
                loader,
                "native/linux-x64/libquill_core.so",
                "libquill_core.so",
                "linux-x64",
            )
            assertTrue(Files.isRegularFile(first), "the first call must produce a file")
            assertTrue(first.readBytes().contentEquals(payload), "the extracted bytes must be the library's")

            val stamp = Files.getLastModifiedTime(first)

            val second = NativeLibraryLoader.resolve(
                loader,
                "native/linux-x64/libquill_core.so",
                "libquill_core.so",
                "linux-x64",
            )

            assertEquals(first, second, "the second call must reuse the same file")
            assertEquals(
                stamp,
                Files.getLastModifiedTime(second),
                "the second call must not rewrite the file — that is the whole point",
            )
        }

        assertEquals(1, extractedFiles(root).size, "one launch, one library on disk")
    }

    @Test
    fun `a rebuilt library replaces the cached one rather than being ignored`() {
        // The failure this guards against is the worst one available: a new version of Quill running
        // last version's engine, which looks like a mysterious bug rather than a stale cache.
        val root = cacheRoot()
        val old = ByteArray(4096) { 1 }
        val new = ByteArray(8192) { 2 }

        val firstJar = jarContaining("native/linux-x64/libquill_core.so", old, "old.jar")
        URLClassLoader(arrayOf(firstJar), null).use { loader ->
            val path = NativeLibraryLoader.resolve(
                loader, "native/linux-x64/libquill_core.so", "libquill_core.so", "linux-x64",
            )
            assertEquals(old.size, path.readBytes().size)
        }

        val secondJar = jarContaining("native/linux-x64/libquill_core.so", new, "new.jar")
        URLClassLoader(arrayOf(secondJar), null).use { loader ->
            val path = NativeLibraryLoader.resolve(
                loader, "native/linux-x64/libquill_core.so", "libquill_core.so", "linux-x64",
            )
            assertTrue(path.readBytes().contentEquals(new), "the rebuilt library must win")
        }

        assertEquals(
            1,
            extractedFiles(root).size,
            "the superseded extraction must be pruned, or the cache grows by a library per release",
        )
    }

    @Test
    fun `a library already on the filesystem is opened where it is`() {
        // A development run, or an exploded application image. Copying a file to arrive at a file is
        // work with nothing to show for it.
        val root = cacheRoot()
        val exploded = temporary.resolve("classes")
        val library = exploded.resolve("native/linux-x64/libquill_core.so")
        Files.createDirectories(library.parent)
        Files.write(library, ByteArray(512) { 7 })

        URLClassLoader(arrayOf(exploded.toUri().toURL()), null).use { loader ->
            val resolved = NativeLibraryLoader.resolve(
                loader, "native/linux-x64/libquill_core.so", "libquill_core.so", "linux-x64",
            )
            assertEquals(library.toRealPath(), resolved.toRealPath())
        }

        assertTrue(extractedFiles(root).isEmpty(), "nothing should have been copied anywhere")
    }
}
