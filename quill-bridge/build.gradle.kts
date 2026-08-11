import dev.starfect.quill.build.CargoBuildTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val jdkToolchain = libs.versions.jdkToolchain.get().toInt()
val jvmTargetVersion = libs.versions.jvmTarget.get()

kotlin {
    // JetBrains Runtime, matching :quill-app -- Jewel's DecoratedWindow requires it, and building
    // both modules on one toolchain keeps the class files consistent.
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkToolchain))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(jvmTargetVersion)
        // Build on the JDK 25 toolchain but emit class files for the oldest JDK that has the
        // finalized java.lang.foreign API. -Xjdk-release also restricts the visible API surface, so
        // a JDK 25-only method cannot sneak in and break the supported baseline.
        freeCompilerArgs.add("-Xjdk-release=$jvmTargetVersion")
        allWarningsAsErrors = true
    }
    explicitApi()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = jvmTargetVersion.toInt()
}

/** Compiles the Rust core and stages the shared library for packaging as a resource. */
val cargoBuild by tasks.registering(CargoBuildTask::class) {
    val crate = layout.projectDirectory.dir("../quill-core")
    crateDirectory.set(crate)
    sourceDirectory.set(crate.dir("src"))
    manifestFile.set(crate.file("Cargo.toml"))
    if (crate.file("Cargo.lock").asFile.exists()) {
        lockFile.set(crate.file("Cargo.lock"))
    }
    crateName.set("quill_core")
    profile.set("release")
    // The task writes `<outputDirectory>/native/<os>-<arch>/`, and this directory is a resource
    // root, so the runtime resource path is `native/<os>-<arch>/<library>`.
    outputDirectory.set(layout.buildDirectory.dir("generated/nativeLibraries"))
}

sourceSets.main {
    // The staged library is a generated resource rather than a checked-in binary, so the source
    // tree stays free of build artifacts.
    resources.srcDir(cargoBuild.map { it.outputDirectory })
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // JDK 24+ warns on restricted native access, and a future release will throw without this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
