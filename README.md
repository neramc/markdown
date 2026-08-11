<div align="center">

<img src="assets/icon.svg" width="96" alt="Quill">

# Quill

**An enterprise Markdown editor with a JetBrains-style workspace and a Rust core engine.**

Compose Multiplatform + Jewel · Rust · Project Panama (FFM) · jlink + ProGuard · Avalonia UI 12

</div>

---

Quill is a desktop Markdown editor built as three cooperating pieces: a Rust engine that owns the
document pipeline, a Kotlin bridge that reaches it through the Foreign Function & Memory API, and a
Compose Multiplatform UI dressed in the IntelliJ Platform's own look through Jewel. Windows setup is
a separate C# / Avalonia application rather than a generated MSI.

<div align="center">
<img src="docs/images/editor-dark.png" width="880" alt="Quill's split view: project tree, tabbed editor with syntax highlighting, rendered preview and structure panel">
</div>

## What it does

| | |
|---|---|
| **IDE workspace** | IntelliJ IDEA New UI: a 40pt main toolbar with the main menu behind a hamburger, icon tool window stripes, editor tabs with file icons and an accent underline, and a status bar of hover widgets. |
| **Split editing** | Source, preview, or both side by side, switched from the toggle group at the editor's top-right or `Ctrl+1/2/3`. |
| **Syntax highlighting** | A line-oriented lexer in Rust colours the source as you type — including half-typed markup, which a structural parse gets wrong. |
| **Rendered preview** | GitHub-flavoured Markdown: tables, task lists, strikethrough, footnotes, alerts, front matter. Fenced code is highlighted by syntect against the active IntelliJ colour scheme. |
| **Find & replace** | Docked at the top of the editor as in the IDE, with `Aa` / `W` / `.*` toggles and the match counter inside the field. Literal, whole-word or regular expression; stepping and replace-all are performed by the engine. |
| **Search Everywhere** | `Ctrl+Shift+P`, with scope tabs, grouped results and subsequence matching over every command. |
| **Outline** | Live document structure, click to jump. |
| **HTML export** | Standalone themed HTML, light or dark. |
| **Welcome window** | Shown when Quill starts with no path: recent projects with their coloured avatars, or large New / Open actions when the list is empty. |
| **Breadcrumbs** | Project, folders, file and the heading the caret sits under, along the status bar. |
| **Themes** | IntelliJ Darcula and Light, switched at runtime. |

## Architecture

```
┌──────────────────── quill-app ─────────────────────────────────────┐
│ Compose Multiplatform + Jewel                                      │
│ Main toolbar · icon stripes · tool windows · editor tabs · split    │
│ SourceEditor (VisualTransformation) │ PreviewPane (Jewel renderer)  │
└────────────────────────────┬───────────────────────────────────────┘
                             │ Kotlin API, suspending, Dispatchers.Default
┌──────────────────── quill-bridge ──────────────────────────────────┐
│ Kotlin facades (AutoCloseable + Cleaner, QWIRE decoding)            │
│ Java downcall layer — MethodHandle.invokeExact                      │
└────────────────────────────┬───────────────────────────────────────┘
                             │ java.lang.foreign downcalls, C ABI
┌──────────────────── quill-core (Rust cdylib) ──────────────────────┐
│ ffi │ document (rope) │ parser → IR │ highlight │ outline │ search  │
│     │ stats │ export                                                │
└─────────────────────────────────────────────────────────────────────┘

  app image ──▶ installer-windows (C# / Avalonia 12)
                QuillSetup.exe · QuillUninstall.exe
```

Three decisions shape everything else:

- **Rust owns the pipeline.** Parsing, highlighting, search, outline, statistics and export all
  happen in Rust. Kotlin's text field holds the editable text and forwards edit deltas into a
  `ropey` rope; everything on screen is derived from the engine.
- **All boundary offsets are UTF-16 code units.** That is the JVM's convention, and converting at
  the boundary is what keeps a Korean syllable (three UTF-8 bytes, one UTF-16 unit) or an emoji (two
  units) from displacing every span after it.
- **One binary wire format, no JSON.** `QWIRE` is a little-endian, length-prefixed, depth-first node
  stream. It keeps a serialization framework and its reflection out of the graph, and every read is
  bounds-checked so a corrupt payload throws instead of over-reading native memory.

Details in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/FFI.md`](docs/FFI.md).

## Building

You need a Rust toolchain; the JetBrains Runtime is provisioned automatically by the Gradle
toolchain resolver.

```bash
./gradlew build                              # Rust engine, bridge, UI, all tests
./gradlew :quill-app:run                     # run it
./gradlew :quill-app:createDistributable     # jlink app image
./gradlew :quill-app:packageReleaseDeb       # ProGuard + jlink + .deb
```

The Windows installer is a separate .NET 10 solution:

```bash
cd installer-windows && dotnet test          # engine and headless UI tests
tools/build-installer.sh --app-image <dir>   # QuillSetup.exe + QuillUninstall.exe
```

See [`docs/BUILD.md`](docs/BUILD.md) and [`docs/INSTALLER.md`](docs/INSTALLER.md).

## Repository layout

| Path | What is in it |
|---|---|
| `quill-core/` | The Rust `cdylib`: FFI surface, rope document, parser, highlighters, search, export. |
| `quill-bridge/` | Java FFM downcalls plus Kotlin facades and the QWIRE decoder. |
| `quill-app/` | The Compose Multiplatform + Jewel application. |
| `installer-windows/` | C# / Avalonia 12 installer, uninstaller, setup engine and tests. |
| `buildSrc/` | The Gradle task that builds and stages the Rust library. |
| `tools/` | Packaging scripts. |
| `docs/` | Architecture, FFI contract, build and installer documentation. |

## Licence

GPL-3.0. See [`LICENSE`](LICENSE).
