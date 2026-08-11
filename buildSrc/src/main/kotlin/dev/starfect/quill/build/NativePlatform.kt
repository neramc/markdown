package dev.starfect.quill.build

import java.util.Locale

/**
 * The operating system and architecture a native library was built for.
 *
 * The identifier this produces is the directory the shared library is staged into and the path the
 * runtime loader looks it up by, so both sides have to agree exactly. Keeping the naming in one
 * class rather than duplicating `System.getProperty("os.name")` parsing in the build and in the
 * loader is what makes that agreement checkable.
 */
public data class NativePlatform(
    /** Normalised operating system name: `linux`, `macos` or `windows`. */
    public val os: String,
    /** Normalised architecture: `x64` or `arm64`. */
    public val arch: String,
) {
    /** The directory name libraries for this platform are staged under. */
    public val identifier: String get() = "$os-$arch"

    /** The file name a cdylib named [crateName] takes on this platform. */
    public fun libraryFileName(crateName: String): String = when (os) {
        "windows" -> "$crateName.dll"
        "macos" -> "lib$crateName.dylib"
        else -> "lib$crateName.so"
    }

    public companion object {
        /** Detects the platform the build is running on. */
        public fun current(): NativePlatform {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val osArch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)

            val os = when {
                osName.contains("win") -> "windows"
                osName.contains("mac") || osName.contains("darwin") -> "macos"
                else -> "linux"
            }

            // The JVM reports the same architecture under several names — amd64 and x86_64 for the
            // same machine, aarch64 and arm64 for another. These spellings are the ones
            // NativeLibraryLoader.platformId() produces, and they have to match it exactly: a
            // mismatch stages the library into a directory nothing ever looks in, and the failure
            // surfaces at startup as a missing library rather than at build time as anything at all.
            val arch = when {
                osArch == "aarch64" || osArch == "arm64" -> "arm64"
                else -> "x64"
            }

            return NativePlatform(os, arch)
        }
    }
}
