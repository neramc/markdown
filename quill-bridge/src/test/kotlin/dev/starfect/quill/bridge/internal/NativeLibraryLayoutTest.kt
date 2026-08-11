package dev.starfect.quill.bridge.internal

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pins the staging layout the build produces to the one the loader reads.
 *
 * The build's `NativePlatform` and [NativeLibraryLoader] each derive an `<os>-<arch>` directory name
 * from the same system properties, in two languages, in two source sets. When they disagree the
 * library is staged somewhere nothing looks, and nothing about that is a build failure — the first
 * symptom is the application starting and reporting that no binary exists for this platform. This
 * test turns that into a test failure instead.
 */
class NativeLibraryLayoutTest {

    @Test
    fun `the staged library is where the loader looks for it`() {
        val platformId = NativeLibraryLoader.platformId()
        val resource = "native/$platformId/${NativeLibraryLoader.libraryFileName(platformId)}"

        assertNotNull(
            javaClass.classLoader.getResourceAsStream(resource),
            "no library at '$resource'; the build and NativeLibraryLoader disagree on the directory name",
        )
    }

    @Test
    fun `architecture aliases collapse to one spelling`() {
        // The JVM reports the same machine as amd64 or x86_64 depending on the platform, and the
        // same ARM machine as aarch64 or arm64. Whichever it says, one directory name comes out.
        assertEquals("linux-x64", NativeLibraryLoader.platformId("Linux", "amd64"))
        assertEquals("linux-x64", NativeLibraryLoader.platformId("Linux", "x86_64"))
        assertEquals("linux-arm64", NativeLibraryLoader.platformId("Linux", "aarch64"))
        assertEquals("macos-arm64", NativeLibraryLoader.platformId("Mac OS X", "arm64"))
        assertEquals("windows-x64", NativeLibraryLoader.platformId("Windows 11", "amd64"))
    }

    @Test
    fun `each platform gets its own library file name`() {
        assertEquals("libquill_core.so", NativeLibraryLoader.libraryFileName("linux-x64"))
        assertEquals("libquill_core.dylib", NativeLibraryLoader.libraryFileName("macos-arm64"))
        assertEquals("quill_core.dll", NativeLibraryLoader.libraryFileName("windows-x64"))
    }
}
