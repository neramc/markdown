package dev.starfect.quill.bridge.internal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Locates and opens the Quill core shared library.
 *
 * <p>The library ships as a classpath resource under {@code native/<os>-<arch>/}, which is what lets
 * one distribution carry binaries for several platforms. {@link SymbolLookup} needs a real file, so
 * the resource is extracted to a temporary file before it is opened.
 */
public final class NativeLibraryLoader {

    /** Overrides resource lookup with a path on disk, for iterating on the Rust side locally. */
    public static final String PATH_PROPERTY = "quill.native.path";

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
        String resourcePath = "native/" + platformId + "/" + libraryFileName(platformId);

        ClassLoader loader = NativeLibraryLoader.class.getClassLoader();
        try (InputStream resource = loader.getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new UnsatisfiedLinkError(
                        "No Quill core library for this platform on the classpath (looked for '"
                                + resourcePath
                                + "'). Build it with ':quill-bridge:cargoBuild', or point -D"
                                + PATH_PROPERTY
                                + " at a local build.");
            }

            Path directory = Files.createTempDirectory("quill-native");
            Path target = directory.resolve(libraryFileName(platformId));
            Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);

            // Best-effort cleanup: on Windows a loaded DLL cannot be deleted while the process holds
            // it, so a failure here is expected and must not take the application down.
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
}
