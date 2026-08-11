# Contributing to Quill

Thanks for taking the time. This document covers how to get the project building, what a change
needs to carry before it can be merged, and where the parts that are easy to get wrong live.

## Getting set up

You need:

- **A Rust toolchain** (stable). `rustup` is the usual way.
- **Nothing else.** The JDK is provisioned by the Gradle toolchain resolver, which reads the vendor
  and version from `gradle/libs.versions.toml`. Do not install one by hand and do not change
  `org.gradle.java.home`.
- **A .NET 10 SDK**, but only if you are working on `installer-windows/`.

```bash
git clone https://github.com/neramc/quill
cd quill
./gradlew build
```

The first build compiles the Rust engine, so it takes a few minutes. Later ones do not: the cargo
task is cacheable and only re-runs when `quill-core/src` changes.

## Running the tests

```bash
cargo test --manifest-path quill-core/Cargo.toml   # engine
./gradlew :quill-bridge:test                       # ABI, against the real cdylib
./gradlew :quill-app:test                          # UI, composed offscreen
dotnet test installer-windows/Quill.Setup.sln      # installer
./gradlew build                                    # all of the JVM side at once
```

Two of these deserve a note.

**`:quill-bridge:test` is what proves the ABI.** The engine's own tests verify its logic against
itself; only the bridge suite verifies that the C signatures, struct layout, offset convention and
buffer ownership actually agree across the boundary. If you change anything in `quill-core/src/ffi/`
or `quill-core/src/wire.rs`, this is the suite that catches what you broke.

**`:quill-app:test` renders the UI without a display.** `ImageComposeScene` composes into a Skia
raster surface, so layout, drawing and text shaping all run with no window and no GL context. Every
frame is written to `quill-app/build/test-renders/`; look at them when a UI change does not do what
you expected. The assertions are structural rather than pixel-exact on purpose — a golden image
fails on any machine with different fonts installed.

## Before you open a pull request

Run these. CI runs them too, and a red build is slower for everyone than a local check.

```bash
cargo fmt --manifest-path quill-core/Cargo.toml
cargo clippy --manifest-path quill-core/Cargo.toml --all-targets -- -D warnings
./gradlew build
```

`clippy` is `-D warnings` in CI. If a lint is genuinely wrong for a piece of code, `#[allow]` it at
the narrowest possible scope with a comment saying why, rather than at the module or crate level.

## What a change needs to carry

**Tests.** A bug fix needs a test that fails before it and passes after. A feature needs tests for
the cases it is meant to handle and the ones it is meant to reject. If a change is genuinely
untestable here — Windows registry behaviour, macOS packaging — say so in the pull request rather
than leaving it unexplained.

**Comments that say why.** The codebase explains reasoning, not mechanics. `// increment the
counter` above `counter += 1` is noise; a note explaining that the parser caps its descent because
the preview re-parses on every keystroke and MDX passes raw HTML straight through is what a reader
needs six months later. Match the density of the code around you.

**A commit message that explains the change.** A subject line in the imperative mood, then a body
covering what changed and why it needed to. If the change fixes something subtle, describe the
failure — the symptom is usually more useful to the next person than the fix.

## Things that are easy to get wrong

These have each cost a debugging session at least once.

**Every offset crossing the FFI boundary is a UTF-16 code unit.** Not bytes, not chars. The engine
stores UTF-8 in a rope and converts at its own boundary; `Document::byte_to_utf16` is the only place
that conversion belongs. A byte offset that leaks through misplaces every span after the first
non-ASCII character, and the symptom — highlighting that drifts right in a document containing one
emoji — looks nothing like the cause. Test with Korean text and an astral emoji, as the existing
tests do.

**The downcall layer has to be Java.** `MethodHandle.invokeExact` is signature-polymorphic: the
compiler takes the descriptor from the call site. Kotlin compiles it as an ordinary varargs call and
it fails at run time with `WrongMethodTypeException: cannot convert MethodHandle()int to
(Object[])Object`. Add new downcalls to `QuillBindings.java`, not to a Kotlin file.

**Both sides of QWIRE change together.** A new payload kind or a changed field order needs the
encoder in `quill-core/src/wire.rs`, the decoder in `quill-bridge/.../wire/`, and a round-trip test.
The magic and version in the header exist to catch a mismatch at run time, but catching it in a test
is better.

**A new inspection needs a stable id.** `Inspection`'s variants and their discriminants are the wire
contract. Append new ones; do not renumber.

**ProGuard is not obfuscating, and must not start.** The release build shrinks and optimises with
renaming off. Renaming breaks Compose's reflective lookups over generated composable classes and
rewrites the polymorphic call descriptors in the bridge. If you add a dependency that gets shrunk
away, add a `-keep` rule to `quill-app/proguard-rules.pro` with a comment saying what needed it.

## Repository conventions

- **Branches** off `main`. Rebase rather than merge to bring in changes.
- **British spelling** in comments and documentation, since that is what the codebase uses.
- **`.editorconfig`** carries the formatting rules; most editors apply it automatically.
- **Screenshots** in `docs/images/` come from `quill-app/build/test-renders/`. Regenerate them
  rather than cropping a real window, so they stay reproducible.

## Reporting bugs

Open an issue with the version, the platform, what you did and what happened. For anything involving
rendering, the document that triggers it is worth more than a description of it — reduce it as far
as you can and paste the whole thing.

Security issues go through [`SECURITY.md`](SECURITY.md) instead.
