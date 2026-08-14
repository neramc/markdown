package dev.starfect.quill.bridge.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Locates and opens the Quill core shared library.
 *
 * <p>The library ships as a classpath resource under {@code native/<os>-<arch>/}, which is what lets
 * one distribution carry binaries for several platforms. {@link SymbolLookup} needs a real file, so
 * the resource has to reach the disk before it can be opened.
 *
 * <p><b>It reaches the disk once, not once per launch.</b> The library is five and a half megabytes
 * and is stored compressed in the jar, so extracting it costs an inflate and a write every time —
 * measured at a little over a tenth of a second, spent before the window can begin to appear. So the
 * extracted file is kept in the user's cache directory under a key derived from the resource itself,
 * and a launch that finds it there does no I/O beyond opening it.
 *
 * <p>The key is the resource's size and timestamp rather than a hash of its contents: hashing five
 * megabytes costs seventy milliseconds, which would hand back most of what the cache saves. Size and
 * timestamp come from the jar's own directory entry and cost nothing to read. They are enough — a
 * rebuilt library changes both, and the failure mode of the pair colliding is a stale library, which
 * the ABI check downstream catches and reports.
 */
public final class NativeLibraryLoader {

    /** Overrides resource lookup with a path on disk, for iterating on the Rust side locally. */
    public static final String PATH_PROPERTY = "quill.native.path";

    /** Overrides where the extracted library is kept, for tests. */
    public static final String CACHE_PROPERTY = "quill.native.cache";

    private static final String CRATE_NAME = "quill_core";

    private NativeLibraryLoader() {}

    /**
     * Identifies the running platform using the same {@code <os>-<arch>} vocabulary as the build's
     * {@code NativePlatform}. The two must agree or the resource lookup silently misses.
     */
    public static String platformId() {
        return platformId(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platformId(String osName, String osArch) {
        String lowerOs = osName.toLowerCase(Locale.ROOT);
        String os;
        if (lowerOs.contains("win")) {
            os = "windows";
        } else if (lowerOs.contains("mac") || lowerOs.contains("darwin")) {
            os = "macos";
        } else {
            os = "linux";
        }

        String lowerArch = osArch.toLowerCase(Locale.ROOT);
        String arch = (lowerArch.equals("aarch64") || lowerArch.equals("arm64")) ? "arm64" : "x64";
        return os + "-" + arch;
    }

    static String libraryFileName(String platformId) {
        if (platformId.startsWith("windows")) {
            return CRATE_NAME + ".dll";
        }
        if (platformId.startsWith("macos")) {
            return "lib" + CRATE_NAME + ".dylib";
        }
        return "lib" + CRATE_NAME + ".so";
    }

    /**
     * Opens the native library and returns a lookup bound to {@code arena}.
     *
     * @throws UnsatisfiedLinkError if no binary is available for this platform
     */
    public static SymbolLookup load(Arena arena) {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!Files.exists(path)) {
                throw new UnsatisfiedLinkError(
                        PATH_PROPERTY + " points at '" + override + "', which does not exist");
            }
            return SymbolLookup.libraryLookup(path, arena);
        }
        return SymbolLookup.libraryLookup(extract(), arena);
    }

    private static Path extract() {
        String platformId = platformId();
        String fileName = libraryFileName(platformId);
        return resolve(
                NativeLibraryLoader.class.getClassLoader(),
                "native/" + platformId + "/" + fileName,
                fileName,
                platformId);
    }

    /**
     * The library as a file on disk, extracting it from the classpath only when it is not already
     * there. Separated from {@link #load} so the caching can be tested without opening anything.
     */
    static Path resolve(ClassLoader loader, String resourcePath, String fileName, String platformId) {
        URL resource = loader.getResource(resourcePath);
        if (resource == null) {
            throw new UnsatisfiedLinkError(
                    "No Quill core library for this platform on the classpath (looked for '"
                            + resourcePath
                            + "'). Build it with ':quill-bridge:cargoBuild', or point -D"
                            + PATH_PROPERTY
                            + " at a local build.");
        }

        // A library already sitting on the filesystem — an exploded classpath, or a development run
        // against the build directory — is opened where it is. Copying a file to reach a file is
        // work with nothing to show for it.
        Path direct = asExistingFile(resource);
        if (direct != null) {
            return direct;
        }

        long size = -1;
        long modified = 0;
        try {
            URLConnection connection = resource.openConnection();
            connection.setUseCaches(true);
            size = connection.getContentLengthLong();
            modified = connection.getLastModified();
        } catch (IOException ignored) {
            // Falls through to a key that omits them, which simply extracts more often.
        }

        Path cached = cacheDirectory().resolve(platformId).resolve(size + "-" + modified).resolve(fileName);
        if (size >= 0 && isUsable(cached, size)) {
            return cached;
        }

        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new UnsatisfiedLinkError("The Quill core library vanished from the classpath mid-load");
            }

            Path directory = cached.getParent();
            Files.createDirectories(directory);

            // Written beside its destination and moved into place, so a second process starting at
            // the same moment either sees no file or sees a whole one. A half-written library that
            // looks complete is the one outcome worth engineering against here: it fails at dlopen,
            // on every subsequent launch, until somebody clears the cache by hand.
            Path partial = Files.createTempFile(directory, fileName, ".partial");
            try {
                Files.copy(stream, partial, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(partial, cached, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException retry) {
                    Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(partial);
            }

            pruneOtherVersions(directory);
            return cached;
        } catch (IOException failure) {
            // The cache directory may be unwritable — a locked-down machine, a full disk, a
            // read-only home. That is a reason to be slow, not a reason not to start.
            return extractToTemporaryFile(loader, resourcePath, fileName);
        }
    }

    /** The resource as a plain file, or null when it lives inside an archive. */
    private static Path asExistingFile(URL resource) {
        if (!"file".equals(resource.getProtocol())) {
            return null;
        }
        try {
            Path path = Path.of(resource.toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isUsable(Path candidate, long expectedSize) {
        try {
            return Files.isRegularFile(candidate) && Files.size(candidate) == expectedSize;
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Removes extractions of other versions of the library for this platform.
     *
     * <p>Without this the cache grows by five megabytes per release, forever, in a directory nobody
     * thinks to look in. Failures are ignored: another process may hold an older library open, and
     * Windows will not delete a mapped file.
     */
    private static void pruneOtherVersions(Path keep) {
        Path parent = keep.getParent();
        if (parent == null) {
            return;
        }
        try (var entries = Files.list(parent)) {
            entries.filter(Files::isDirectory)
                    .filter(entry -> !entry.equals(keep))
                    .forEach(NativeLibraryLoader::deleteQuietly);
        } catch (IOException ignored) {
            // Best effort by design.
        }
    }

    private static void deleteQuietly(Path directory) {
        try (var entries = Files.list(directory)) {
            entries.forEach(
                    entry -> {
                        try {
                            Files.deleteIfExists(entry);
                        } catch (IOException ignored) {
                            // As above.
                        }
                    });
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // As above.
        }
    }

    /** The pre-cache behaviour, kept for when the cache cannot be written. */
    private static Path extractToTemporaryFile(ClassLoader loader, String resourcePath, String fileName) {
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new UnsatisfiedLinkError("The Quill core library vanished from the classpath mid-load");
            }
            Path directory = Files.createTempDirectory("quill-native");
            Path target = directory.resolve(fileName);
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);

            // On Windows a loaded DLL cannot be deleted while the process holds it, so this failing
            // is expected and must not take the application down.
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            Files.deleteIfExists(target);
                                            Files.deleteIfExists(directory);
                                        } catch (IOException ignored) {
                                            // Nothing useful to do during shutdown.
                                        }
                                    },
                                    "quill-native-cleanup"));
            return target;
        } catch (IOException failure) {
            throw new UnsatisfiedLinkError("Failed to extract the Quill core library: " + failure.getMessage());
        }
    }

    /** Where extracted libraries are kept: the platform's cache directory, not its config one. */
    static Path cacheDirectory() {
        String configured = System.getProperty(CACHE_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home", "."));

        if (osName.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local != null ? Path.of(local) : home.resolve("AppData").resolve("Local");
            return base.resolve("Quill").resolve("native");
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return home.resolve("Library").resolve("Caches").resolve("Quill").resolve("native");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".cache");
        return base.resolve("Quill").resolve("native");
    }
}
