package dev.starfect.quill.build

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Builds the Rust crate and stages its shared library where the JVM build can pick it up as a
 * resource.
 *
 * The task is cacheable and declares its inputs precisely — the crate sources, the manifest and the
 * lock file — so an unchanged Rust tree costs nothing on a rebuild. That matters more than usual
 * here: a release `cargo build` of the engine is by far the slowest step in the whole build, and
 * without accurate inputs Gradle would rerun it on every Kotlin edit.
 *
 * Output layout is `<outputDirectory>/native/<os>-<arch>/<library>`, which is registered as a
 * resource root, so the library travels inside the jar and the loader finds it at the resource path
 * `native/<os>-<arch>/<library>`.
 */
@CacheableTask
public abstract class CargoBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** The crate root, i.e. the directory holding `Cargo.toml`. */
    @get:Internal
    public abstract val crateDirectory: DirectoryProperty

    /** The crate's sources. Declared separately so Gradle can hash them for up-to-date checks. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val sourceDirectory: DirectoryProperty

    /** `Cargo.toml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val manifestFile: RegularFileProperty

    /**
     * `Cargo.lock`, when the crate has one.
     *
     * Optional because a fresh checkout may not have one yet, and failing the build for that would
     * be worse than rebuilding once more than strictly necessary.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val lockFile: RegularFileProperty

    /** The crate name, used to derive the library file name. */
    @get:Input
    public abstract val crateName: Property<String>

    /** Cargo profile: `release` or `debug`. */
    @get:Input
    public abstract val profile: Property<String>

    /** Where the staged library tree is written. */
    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    init {
        group = "build"
        description = "Builds the Rust core library and stages it as a JVM resource."
    }

    @TaskAction
    public fun build() {
        val crate = crateDirectory.get().asFile
        val platform = NativePlatform.current()
        val profileName = profile.get()

        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(buildList {
                add("cargo")
                add("build")
                if (profileName == "release") add("--release")
                add("--manifest-path")
                add(manifestFile.get().asFile.absolutePath)
            })
            workingDir = crate
            // Cargo writes progress to stderr even when it succeeds, so it is captured and only
            // surfaced when the build actually fails; otherwise every build prints a wall of output.
            errorOutput = stderr
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw GradleException(
                "cargo build failed with exit code ${result.exitValue}.\n" +
                    stderr.toString(Charsets.UTF_8).ifBlank { "cargo produced no diagnostics." } +
                    "\nIs a Rust toolchain installed and on PATH? See docs/BUILD.md.",
            )
        }

        val libraryName = platform.libraryFileName(crateName.get())
        val built = crate.resolve("target").resolve(profileName).resolve(libraryName)
        if (!built.isFile) {
            throw GradleException(
                "cargo reported success but no library was produced at $built. " +
                    "Check that the crate declares crate-type = [\"cdylib\"].",
            )
        }

        val staged = outputDirectory.get().asFile
            .resolve("native")
            .resolve(platform.identifier)

        // The staging directory is cleared rather than merged: a stale library from a previous
        // build with a different name would otherwise be packaged alongside the current one and
        // whichever the loader found first would win.
        if (staged.exists() && !staged.deleteRecursively()) {
            throw GradleException("Could not clear the staging directory $staged.")
        }
        if (!staged.mkdirs()) {
            throw GradleException("Could not create the staging directory $staged.")
        }

        built.copyTo(staged.resolve(libraryName), overwrite = true)
        logger.lifecycle("Staged $libraryName for ${platform.identifier}")
    }
}
