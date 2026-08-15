import dev.starfect.quill.build.NativePlatform
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

val jdkToolchain = libs.versions.jdkToolchain.get().toInt()
val jvmTargetVersion = libs.versions.jvmTarget.get()
val quillVersion = version.toString()

kotlin {
    // JetBrains Runtime, not a stock JDK: Jewel's DecoratedWindow uses JBR-only window decoration
    // APIs and refuses to start elsewhere. JBR 25 is also past JDK 22, so the finalized
    // java.lang.foreign API is available.
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchain))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        freeCompilerArgs.addAll(
            "-Xjdk-release=$jvmTargetVersion",
            // Jewel's Markdown and theming APIs are annotated experimental. Opting in here, once, is
            // clearer than scattering @OptIn across every composable.
            "-opt-in=org.jetbrains.jewel.foundation.ExperimentalJewelApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = jvmTargetVersion.toInt()
}

// The repository's brand assets become classpath resources, so window and title-bar icons load
// through the normal resource mechanism rather than from a path relative to the working directory.
tasks.processResources {
    from(rootProject.file("assets")) { into("icons") }
}

dependencies {
    implementation(project(":quill-bridge"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    // Jewel: the IntelliJ Platform look and feel for Compose for Desktop.
    implementation(libs.jewel.intUi.standalone)
    implementation(libs.jewel.decoratedWindow)
    implementation(libs.jewel.markdown.core)
    implementation(libs.jewel.markdown.intUiStyling)
    implementation(libs.jewel.markdown.ext.strikethrough)
    implementation(libs.jewel.markdown.ext.alerts)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // Print the assertion, not Gradle's stack. A failing build on a CI runner otherwise reports two
    // hundred lines of org.gradle.internal.execution frames with the one line that says what went
    // wrong scrolled off the top -- which is how a five-minute diagnosis becomes an hour.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

// The Compose plugin's run and packaging tasks default to the JVM running Gradle, which may be older
// than the toolchain the code is compiled for. Pointing them at the same JetBrains Runtime toolchain
// is what makes `run` and `jpackage` agree with `compileKotlin`.
val toolchainLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(jdkToolchain))
    vendor.set(JvmVendorSpec.JETBRAINS)
}

compose.desktop {
    application {
        mainClass = "dev.starfect.quill.MainKt"
        javaHome = toolchainLauncher.get().metadata.installationPath.asFile.absolutePath

        jvmArgs += listOf(
            // Panama's downcalls are "restricted" methods. JDK 24+ warns without this, and a future
            // release will refuse outright.
            "--enable-native-access=ALL-UNNAMED",
            "-Dquill.version=$quillVersion",
            "-Dapple.awt.application.appearance=system",
        )

        buildTypes.release.proguard {
            version = libs.versions.proguard.get()
            optimize = true
            // Obfuscation buys nothing for a desktop application and breaks the reflective lookups
            // Compose and Jewel rely on. Shrinking plus optimisation is where the size win is.
            obfuscate = false
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            // Windows is served by the separate Avalonia installer under installer-windows/, which
            // consumes the app image this build produces.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)

            packageName = "Quill"
            packageVersion = quillVersion
            description = "An enterprise Markdown editor with a Rust core"
            copyright = "Copyright (c) 2026 Quill contributors"
            vendor = "starfect"

            // Explicit module list keeps the jlink runtime image small: without it the plugin has to
            // guess, and guessing wrong either bloats the image or breaks at runtime.
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.prefs",
                "java.management",
                "jdk.unsupported",
            )

            // jpackage wants rasterised icons, but the repository ships vector art. Each format is
            // wired up only when it has been generated (see docs/BUILD.md), so a plain checkout
            // still packages successfully with the platform default icon.
            linux {
                val png = rootProject.file("assets/icon.png")
                if (png.exists()) iconFile.set(png)
                menuGroup = "Development"
                appCategory = "Development"
                debMaintainer = "quill@starfect.dev"
            }
            macOS {
                bundleID = "dev.starfect.quill"
                val icns = rootProject.file("assets/icon.icns")
                if (icns.exists()) iconFile.set(icns)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ size

/*
 * An installed Quill was 157 MB, and roughly none of that was Quill.
 *
 * The three biggest items were the bundled Java runtime's module image (55 MB), Skia (28 MB) and
 * the JVM itself (28 MB) — so the way to make the application smaller was to stop shipping so much
 * of other people's software uncompressed and unstripped. Three changes below, each measured on the
 * real image:
 *
 *   compress the runtime  101 MB → 71 MB
 *   strip the natives      -9 MB
 *   drop unused fonts    -6.7 MB
 *
 * Nothing here changes what the application does; every byte removed is either a duplicate of what
 * a decompressor can regenerate, a symbol table, or a font nothing asks for.
 */

/**
 * Turns on jlink's zip compression for the bundled runtime.
 *
 * The Compose plugin models this — `AbstractJLinkTask` has a `compressionLevel` property, and passes
 * it through to `jlink --compress` — but never sets it and does not expose it in the DSL, so the
 * runtime ships uncompressed. On this module list that is the single largest saving available:
 * `lib/modules` goes from 55 MB to 26 MB and the whole runtime from 101 MB to 71 MB, for a runtime
 * that behaves identically. (`--strip-debug` the plugin *does* pass, which is why the uncompressed
 * image is 55 MB rather than the 65 MB plain jlink produces.)
 *
 * Reaching an internal property by reflection is not free, so it fails loudly rather than quietly:
 * a plugin upgrade that renames this would otherwise put 30 MB back into every release with nothing
 * to notice it. `ZIP` maps to `jlink --compress 2`, which is zip-6 — zip-9 measured identically on
 * this image and costs link time for nothing.
 */
fun compressBundledRuntime(task: Task) {
    val levels = Class.forName(
        "org.jetbrains.compose.desktop.application.internal.RuntimeCompressionLevel",
        false,
        task.javaClass.classLoader,
    )
    val zip = levels.enumConstants.first { (it as Enum<*>).name == "ZIP" }
    val accessor = task.javaClass.methods.firstOrNull { it.name.startsWith("getCompressionLevel") }
        ?: error(
            "The Compose plugin's jlink task no longer exposes a compression level. Without it the " +
                "bundled runtime ships uncompressed and every package grows by about 30 MB — find " +
                "the replacement in AbstractJLinkTask rather than removing this.",
        )

    @Suppress("UNCHECKED_CAST")
    (accessor.invoke(task) as org.gradle.api.provider.Property<Any>).set(zip)
}

tasks.withType(org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask::class.java)
    .configureEach { compressBundledRuntime(this) }

/**
 * The fonts Quill asks for, which is nine of the forty-three the JetBrains Runtime carries.
 *
 * `UiFonts` loads Inter for the interface and JetBrains Mono for the editor out of the runtime's own
 * `lib/fonts`. Everything else there — Droid Sans, Droid Serif, Fira Code, Inconsolata and eight
 * JetBrains Mono weights nothing references — is 6.7 MB that ships in every package on every
 * platform and is never opened.
 *
 * This list and `UiFonts.FACES` have to say the same thing, and both directions of drift are silent:
 * a face added to `UiFonts` and not here is deleted on its way into the package, and one removed
 * from `UiFonts` and left here just wastes space. `UiFontsTest` compares the two and names this file
 * when they disagree — a test rather than a clever read of the Kotlin source, because a build script
 * that parses code is a second thing to get wrong.
 *
 * [shrinkAppImage] covers the third case: a runtime that no longer carries a face named here.
 */
val bundledFonts = listOf(
    "Inter-Regular.otf", "Inter-Italic.otf", "Inter-SemiBold.otf", "Inter-SemiBoldItalic.otf",
    "JetBrainsMono-Regular.ttf", "JetBrainsMono-Italic.ttf", "JetBrainsMono-Bold.ttf",
    "JetBrainsMono-BoldItalic.ttf", "JetBrainsMono-Medium.ttf",
)

/**
 * Strips symbol tables from the bundled native libraries.
 *
 * Skia and the JVM arrive carrying full symbol tables — 9 MB between them on Linux — which nothing
 * in a shipped application reads. The cost is that a native crash log names fewer frames, which is
 * the trade every distributed JVM already makes; JBR is unusual in not making it.
 *
 * Best effort by design. `strip` is a build tool, not a dependency: if it is missing, or refuses a
 * particular file, the package is a few megabytes larger and still correct. Failing a release build
 * over a size optimisation would be the wrong trade in the other direction.
 */
fun shrinkAppImage(image: File, logger: org.gradle.api.logging.Logger) {
    if (!image.isDirectory) return

    val fonts = image.walkTopDown().firstOrNull { it.isDirectory && it.path.endsWith("lib/fonts") }
    if (fonts != null) {
        val present = fonts.listFiles().orEmpty().map { it.name }.toSet()
        val missing = bundledFonts.filterNot { it in present }
        check(missing.isEmpty()) {
            "the runtime no longer carries ${missing.joinToString()}, which UiFonts loads by name — " +
                "update both this list and UiFonts, or the application ships with a fallback font"
        }
        fonts.listFiles().orEmpty().filter { it.name !in bundledFonts }.forEach { it.delete() }
    }

    val stripper = when {
        org.gradle.internal.os.OperatingSystem.current().isLinux -> listOf("strip", "--strip-unneeded")
        // Apple's strip needs -x: a plain strip of a shared library removes symbols the dynamic
        // linker still needs and produces a dylib that will not load.
        org.gradle.internal.os.OperatingSystem.current().isMacOsX -> listOf("strip", "-x", "-S")
        // Windows keeps debug information in separate .pdb files that are not shipped anyway.
        else -> return
    }

    var saved = 0L
    image.walkTopDown()
        .filter { it.isFile && (it.name.endsWith(".so") || it.name.endsWith(".dylib")) }
        .forEach { library ->
            val before = library.length()
            val result = runCatching {
                providers.exec {
                    commandLine(stripper + library.absolutePath)
                    isIgnoreExitValue = true
                }.result.get().exitValue
            }
            if (result.isSuccess && result.getOrNull() == 0) saved += before - library.length()
        }

    if (saved > 0) {
        logger.lifecycle("Stripped ${saved / 1024 / 1024} MB of symbols from the bundled libraries")
    } else {
        logger.info("strip produced no saving; the packaged libraries keep their symbol tables")
    }
}

tasks.withType(org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask::class.java)
    .configureEach {
        val log = logger
        doLast {
            // The app image directory, whatever the task called it. Only app-image builds have one;
            // the .deb/.rpm/.dmg tasks package an image that has already been through this.
            destinationDir.get().asFile.listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { shrinkAppImage(it, log) }
        }
    }

// --------------------------------------------------------------------------------- portable build

/**
 * The application image as a single archive.
 *
 * The native packages — `.deb`, `.rpm`, `.dmg`, and the Windows installer — cover the ordinary case,
 * and each of them assumes something: a package manager, an administrator, a writable `/opt`. None
 * of that holds for a locked-down work machine, for a distribution that uses neither dpkg nor rpm
 * (Arch, NixOS, Alpine, Void), or for anybody who simply wants to try an editor without installing
 * it. Those people are otherwise offered nothing at all, which is why this exists.
 *
 * The archive is the jlink image itself, so it carries its own Java runtime and its own copy of the
 * Rust library: unpack it anywhere and run `bin/Quill`. There is no system-wide state to undo — the
 * uninstall is `rm -r`.
 *
 * Tar on the Unixes and Zip on Windows, because that is what each platform can open without
 * installing something first. The distinction matters more than it looks: zip has no concept of a
 * file being executable, so a zipped launcher arrives without its executable bit and does not
 * start. Tar records the mode, which is why it is the format everywhere the mode means something.
 */
val nativePlatform = NativePlatform.current()
val portableName = "Quill-$quillVersion-${nativePlatform.identifier}"
val releaseAppImage = layout.buildDirectory.dir("compose/binaries/main-release/app")
val portableDirectory = layout.buildDirectory.dir("portable")

if (nativePlatform.os == "windows") {
    tasks.register<Zip>("packagePortable") {
        group = "distribution"
        description = "Packages the release application image as a zip archive"
        dependsOn("createReleaseDistributable")
        from(releaseAppImage)
        archiveFileName.set("$portableName.zip")
        destinationDirectory.set(portableDirectory)
    }
} else {
    tasks.register<Tar>("packagePortable") {
        group = "distribution"
        description = "Packages the release application image as a gzipped tar archive"
        dependsOn("createReleaseDistributable")
        from(releaseAppImage)
        archiveFileName.set("$portableName.tar.gz")
        compression = Compression.GZIP
        destinationDirectory.set(portableDirectory)
        // The launcher and every binary in the bundled runtime have to stay executable. Gradle's
        // archive tasks carry the source permissions through unless told otherwise, and this asserts
        // that rather than assuming it: an archive that unpacks to a launcher nobody can run is
        // indistinguishable from a working one until somebody tries it.
        //
        // The launcher is in a different place on each platform — `Quill/bin/Quill` on Linux,
        // `Quill.app/Contents/MacOS/Quill` on macOS — so it is searched for rather than named. A
        // check that hard-codes one layout does not verify the other platform, it fails it.
        doLast {
            val image = releaseAppImage.get().asFile
            val launcher = image.walkTopDown()
                .firstOrNull { it.isFile && it.name == "Quill" && it.parentFile.name in setOf("bin", "MacOS") }
            checkNotNull(launcher) { "no launcher found under $image, so there is nothing to package" }
            check(launcher.canExecute()) {
                "the packaged launcher at $launcher is not executable, so the archive will not be either"
            }
        }
    }
}
