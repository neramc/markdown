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
rendering — harmless, and the reason `Cannot create Linux GL context` appears in launch logs. It
also has no screen-capture tooling; the screenshots in `docs/images/` come from the offscreen render
tests rather than from a window manager.
