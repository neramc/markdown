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
