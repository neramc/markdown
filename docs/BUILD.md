# Building Quill

## Prerequisites

| | |
|---|---|
| **Rust** | Stable toolchain with edition 2024 support (1.85+). `rustup` is enough; no C compiler or Oniguruma needed — syntect uses the `fancy-regex` backend. |
| **JDK** | None required up front. The Gradle toolchain resolver downloads **JetBrains Runtime 25** automatically. |
| **.NET** | Only for the Windows installer: .NET 10 SDK. See [`INSTALLER.md`](INSTALLER.md). |
| **fakeroot** | Only for `packageReleaseDeb` on Linux; jpackage shells out to it. |

## Everyday commands

```bash
./gradlew build                           # engine, bridge, UI and every test
./gradlew :quill-app:run                  # run the editor
cargo test  --manifest-path quill-core/Cargo.toml
cargo clippy --manifest-path quill-core/Cargo.toml --all-targets -- -D warnings
```

`./gradlew build` runs `cargo build --release` through a cacheable Gradle task, so the Rust library
is rebuilt only when its sources change.

## Toolchain choices, and why

**JetBrains Runtime, not a stock JDK.** Jewel's `DecoratedWindow` uses JBR-only window decoration
APIs and refuses to start elsewhere. The toolchain therefore pins
`vendor.set(JvmVendorSpec.JETBRAINS)`. JBR 25 also satisfies the JDK 22+ requirement for the
finalized `java.lang.foreign` API, so one toolchain covers both needs.

**Bytecode target 22.** `jvmTarget = 22` plus `-Xjdk-release=22`. Twenty-two is the release that
finalized the FFM API, and it is comfortably inside ProGuard's supported range.

**`google()` in `settings.gradle.kts`.** Compose Multiplatform 1.11 resolves `androidx.collection`,
`androidx.annotation`, `androidx.lifecycle` and `androidx.savedstate` from Google's Maven
repository. Maven Central alone cannot resolve the build.

**Kotlin 2.4.10.** Jewel `0.39.1-262.9437.29` ships class files carrying Kotlin 2.4 metadata.
Compiling against it with an older compiler fails on every Jewel type with "metadata version 2.4.0,
expected 2.2.0".

**`application { javaHome = ... }`.** The Compose plugin's `run` and packaging tasks default to the
JVM running Gradle, which may be older than the toolchain the code was compiled for and then fails
with `UnsupportedClassVersionError`. Pointing them at the same toolchain launcher is what makes
`run` and `jpackage` agree with `compileKotlin`.

## The native library

`buildSrc`'s `CargoBuildTask` runs `cargo build --release` and stages the resulting `cdylib` into

```
quill-bridge/build/generated/nativeLibraries/native/<os>-<arch>/
```

which is registered as a resource root. The library therefore travels inside `quill-bridge.jar`,
and `NativeLibraryLoader` extracts it to a temporary directory at startup. Nothing is written into
the source tree.

To point the bridge at a library you built yourself, set `-Dquill.native.path=/path/to/libquill_core.so`.

## Packaging

```bash
./gradlew :quill-app:createDistributable         # jlink app image, no shrinking
./gradlew :quill-app:createReleaseDistributable  # ProGuard, then jlink
./gradlew :quill-app:packageReleaseDeb           # .deb   (Linux)
./gradlew :quill-app:packageReleaseRpm           # .rpm   (Linux)
./gradlew :quill-app:packageReleaseDmg           # .dmg   (macOS)
```

There is no `Msi` target: Windows is served by the separate Avalonia installer, which consumes the
app image this build produces.

The jlink module list is explicit (`java.base`, `java.desktop`, `java.logging`, `java.prefs`,
`java.management`, `jdk.unsupported`). Leaving the plugin to infer it either bloats the runtime
image or omits something that only fails at runtime.

## ProGuard

The release build shrinks and optimises but **does not obfuscate**. Renaming buys nothing for a
desktop application that exposes no public API, and it breaks the reflective lookups Compose and
Jewel perform on generated classes. It would also rewrite the descriptors of the bridge's
signature-polymorphic `invokeExact` call sites, which fails at runtime rather than at build time.

`quill-app/proguard-rules.pro` carries the keep rules. Three are load-bearing and were each found by
a failed build:

- **`-keep class com.jetbrains.** { *; }`** — the JetBrains Runtime API resolves every service
  through generated `JBR$Xxx__Holder` classes that nothing references statically. Without this rule
  ProGuard removed 45 of `jbr-api`'s 65 classes, `com.jetbrains.JBR` reported the API as
  unavailable, and the release build threw *"DecoratedWindow can only be used on
  JetBrainsRuntime(JBR)"* while the debug build ran fine.
- **`-dontwarn org.commonmark.**`** — Jewel's Markdown module bundles a commonmark processor Quill
  never calls, whose signatures reference extension types that are not on the classpath. ProGuard
  treats unresolved library members as fatal rather than as dead code.
- **`-dontwarn java.lang.invoke.MethodHandle`** — `invokeExact` is signature-polymorphic, so no
  method with the call site's descriptor exists for ProGuard to resolve. Every such warning is one
  of the bridge's downcalls.

Two more suppress genuinely absent classes: `kotlin.RequiresOptIn**` (source retention, so the
classes exist in no jar) and `org.jetbrains.annotations.**` (compile-time only).

**R8 is not used.** The Compose Desktop Gradle plugin has no supported R8 path for desktop JVM
targets; its `buildTypes.release.proguard` block runs ProGuard. R8's directives are not
interchangeable — `-keepresources` is R8-only and ProGuard's parser rejects it outright. ProGuard
copies non-class jar entries through untouched anyway, so resources need no rule.

## Verification

```bash
cargo test --manifest-path quill-core/Cargo.toml   # engine unit tests
./gradlew :quill-bridge:test                       # FFM tests against the real .so
./gradlew :quill-app:test                          # offscreen UI render tests
```

`quill-app`'s tests use Compose's `ImageComposeScene`, which composes into a Skia raster surface
rather than a window. They exercise layout, drawing and text shaping with no display server and no
GL context, drive the real engine, and write PNGs to `quill-app/build/test-renders/` for
inspection.

## Continuous integration

`.github/workflows/ci.yml` runs three jobs:

1. **`engine`** — `cargo test` and `cargo clippy -D warnings` on Ubuntu.
2. **`jvm`** — a Linux/macOS/Windows matrix running `./gradlew build` and
   `packageReleaseDistributionForCurrentOS`, uploading each app image.
3. **`installer`** — downloads the Windows app image and runs `tools/build-installer.sh`, producing
   `QuillSetup.exe` and `QuillUninstall.exe`.

## Known environment limitations

The container this project was developed in has no GL context, so Skiko falls back to software
rendering — harmless, and the reason `Cannot create Linux GL context` appears in launch logs.

There is no window manager either, which is worth knowing if you drive the app on a virtual display:
nothing routes keyboard input to the window and nothing honours a position request, so both have to
be done by hand through the X server. Pointer events are unaffected, which is enough to click
through the whole UI. The screenshots in `docs/images/` are framebuffer captures of the packaged
release binary running that way — `Xvfb -fbdir` writes the screen to an XWD file, which decodes to a
PNG. `quill-app`'s render tests produce equivalent images offscreen through `ImageComposeScene`, and
those are what CI can check; the captures are what shows the real window.

## Releasing

A release is cut by pushing a tag. The tag is both the trigger and the version:

```bash
# 1. Set the version and write its section in CHANGELOG.md.
#    The workflow refuses to build if these disagree with the tag.
$EDITOR gradle.properties CHANGELOG.md

# 2. Rehearse. This runs the whole pipeline on all five platforms and publishes nothing,
#    leaving the assets and the generated notes as workflow artifacts to inspect.
gh workflow run Release

# 3. Cut it.
git tag v1.2.3 && git push origin v1.2.3
```

### What the pipeline does

| Job | Where | What |
|---|---|---|
| `gate` | Linux | Refuses a tag that disagrees with `quill.version`, refuses a version with no changelog section, then runs the engine's format, lint and test checks. |
| `build` | five platforms | `build` → `createReleaseDistributable` → `packagePortable`, plus `.deb`/`.rpm` on Linux and `.dmg` on macOS. |
| `windows-setup` | Linux | Builds `QuillSetup.exe` around the Windows application image the matrix produced. |
| `publish` | Linux | Renames every asset to one scheme, writes `SHA256SUMS`, and creates the GitHub release from the changelog. |

### Why the gate exists

Both of its checks are for mistakes that are invisible until somebody downloads the result. A tag
that disagrees with `quill.version` produces a release called 1.2.3 containing binaries that report
1.2.2 — and the binaries are right, because they were built from the declared version. A version
with no changelog section produces a release page with nothing on it, which is worse than a late
release. Both cost a minute here and the whole pipeline anywhere else.

### Why five build jobs rather than cross-compilation

jpackage builds for the machine it runs on: the runtime image it bundles is that platform's JVM, and
the Rust library beside it is that platform's shared object. Nothing about the pipeline could
cross-compile without reimplementing both.

That constraint turns out to be the useful shape anyway. Each job is a real build and a real test
run on that platform, so "does it work on an Intel Mac" is answered on every push rather than by
the first person who downloads it.

The one deliberate omission is Windows on ARM. GitHub does offer `windows-11-arm` runners, and
Windows runs x64 binaries on ARM under emulation — so an arm64 Windows package would be a second
download for the same result, and a second thing to choose between.

### Naming

jpackage names its output three different ways for the same machine — `quill_1.0.0_amd64.deb`,
`quill-1.0.0-1.x86_64.rpm`, `Quill-1.0.0.dmg` — and the DMG carries no architecture at all. Two
platforms therefore produce a file with exactly the same name, so collecting them without renaming
silently loses one. `tools/assemble-release.sh` maps everything onto
`Quill-<version>-<platform>.<extension>`; its rules and their reasons are in the script.

### What each platform actually verifies

Four of the five platforms run the whole suite, screenshot tests included: the interface is composed
onto a real canvas and the pixels are asserted on.

**Linux on ARM is the exception.** Skia's native library does not load on GitHub's `ubuntu-24.04-arm`
image — installing the X, OpenGL and fontconfig client libraries it links against is not enough, and
the failure arrives as `LibraryLoadException` before anything is drawn. So the render tests *skip*
there, with the load error in the skip message, and the other 275 tests run normally. The build, the
packaging, the engine, the bridge and every export path are covered on arm64; the drawing is not.

That is a deliberate trade rather than an oversight. Failing would mean no release could be cut
whenever a runner happens to be headless, for a reason with nothing to do with the release. Passing
quietly would mean nobody notices a platform going untested. A skip says what happened, where
somebody reading the results will see it. The distinction is worth keeping in mind when a change
touches the interface: on arm64 Linux, CI will not catch it.
