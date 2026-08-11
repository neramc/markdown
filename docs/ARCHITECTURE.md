# Architecture

Quill is three programs in one process, plus a fourth that installs it. This document explains what
each layer owns and why the boundaries are where they are.

## The shape of the thing

```
┌──────────────────── quill-app (Kotlin, Compose Multiplatform + Jewel) ─────────────┐
│  Main · QuillController · WorkspaceState                                            │
│  ui/shell     DecoratedWindow, TitleBar, tool window stripes, status bar            │
│  ui/editor    SourceEditor (TextArea + VisualTransformation), EditorTabs            │
│  ui/preview   IrToJewel, PreviewPane, EngineCodeHighlighter                         │
│  ui/tools     ProjectTree, OutlinePanel, FindReplaceBar                             │
│  ui/palette   CommandPalette                                                        │
└───────────────────────────────────┬─────────────────────────────────────────────────┘
                                    │ QuillEngine / QuillDocument (Kotlin, AutoCloseable)
┌──────────────────── quill-bridge (Kotlin + Java) ───────────────────────────────────┐
│  QuillEngine, QuillDocument        facades, Cleaner backstop, exception translation  │
│  wire/WireReader, MarkdownIr       QWIRE decoding into IR data classes               │
│  internal/QuillBindings.java       downcall handles, MethodHandle.invokeExact        │
│  internal/NativeLibraryLoader.java extracts the cdylib from the jar                  │
└───────────────────────────────────┬─────────────────────────────────────────────────┘
                                    │ java.lang.foreign, C ABI, UTF-16 offsets
┌──────────────────── quill-core (Rust cdylib) ───────────────────────────────────────┐
│  ffi/            20 extern "C" entry points, catch_unwind, QuillBuf ownership        │
│  document.rs     ropey rope, version counter, per-version result cache               │
│  parser/         comrak GFM AST → block IR carrying source line ranges               │
│  highlight/      editor.rs (line lexer), code.rs (syntect + two-face)                │
│  outline.rs · stats.rs · search.rs · export.rs · theme.rs · wire.rs                  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## Why Rust owns the pipeline

The alternative — Rust as a fast parser and Kotlin doing everything else — would put the same
Markdown knowledge in two languages. Instead the engine produces every derived view of a document:
the block IR the preview renders, the style spans the editor paints, the outline, the statistics,
the search results and the exported HTML. The UI holds the editable text and nothing else that
matters.

That has a consequence worth stating plainly: the UI is always showing derived data that is *at
least* one version behind the text on screen. `QuillController` handles this explicitly rather than
hoping it never matters — see "Staleness" below.

## The editable text lives in Kotlin

Compose's `TextFieldValue` owns the buffer the user types into. On each change,
`TextDelta.between()` trims the common prefix and suffix to produce the smallest single-range edit,
and that range is applied to the engine's rope. Two details matter:

- **Prefix and suffix boundaries are pulled back off surrogate pairs.** Splitting one hands the
  engine an offset that is not a character boundary.
- **A failed incremental edit falls back to a full `set_text`.** The alternative to a visible
  recovery is silent divergence between what the user sees and what the engine believes.

## Derivation: debounced, version-stamped

`QuillController.derive()` waits 120 ms after the last keystroke, then computes blocks, outline,
stats and spans on `Dispatchers.Default`. Each result carries the engine version it was computed
from. On completion the controller compares that against the session's current version and drops the
result if a newer edit has already landed.

Without the stamp, a slow parse of an old version can complete after a fast parse of a new one and
overwrite it — which shows up as the preview flickering back to stale content while typing.

## State updates are serialised

`WorkspaceState` is one immutable value replaced wholesale. Updates arrive from the UI thread and
from every background coroutine the controller launches, and each update is a read-modify-write. A
lock around that read-modify-write is not optional: without it, two overlapping updates silently
discard the earlier one. In practice that looked like a project tree reverting to empty when a file
and a project were opened together, and an opened find bar closing itself when a search result
landed.

## Rendering the preview

The engine's IR is deliberately shaped like Jewel's own `MarkdownBlock` / `InlineMarkdown`
hierarchy, so `IrToJewel` is a flat rename with no restructuring. Jewel then renders it with the
IntelliJ Platform's own Markdown styling — and never parses any Markdown itself. Jewel's bundled
commonmark processor is dead code in this application.

Two exceptions:

- **Tables.** Jewel renders tables through an extension whose node type is not publicly
  constructible, so Quill draws them itself in `PreviewPane.MarkdownTable` using the shell palette.
- **Fenced code.** Jewel exposes a `CodeHighlighter` seam; `EngineCodeHighlighter` implements it by
  calling back into the engine, where syntect resolves colours against the active IntelliJ scheme.
  It emits the unstyled text first so a block appears immediately, then replaces it — Jewel's
  contract explicitly supports that progressive pattern.

## Highlighting the source is a lexer, not a parse

`highlight/editor.rs` scans line by line rather than walking the AST. An editor colours half-typed
markup, and a structural parse of half-typed markup disagrees with what the user sees: type `**bo`
and a parser sees a paragraph containing literal asterisks, while the user expects the emphasis to
have begun.

Two cases the line lexer gets right because it was made to:

- A leading `---` is front matter only if a closing delimiter exists later; otherwise it is a
  thematic break.
- A `---` directly under paragraph text is a setext heading, not a break.

## Looking like IntelliJ IDEA

Jewel supplies IntelliJ's *components* — buttons, fields, menus, the Markdown renderer — but not the
IDE's *layout*, and the layout is most of what makes a window recognisable. `ui/theme/IdeaMetrics.kt`
holds the New UI's measurements in one place, and `ui/theme/QuillTheme.kt` holds its palette, so the
shell is built from the IDE's own numbers rather than from per-component guesses.

The details that carry the resemblance, and what each replaced:

| Element | New UI behaviour | What a naive build does instead |
|---|---|---|
| Main menu | A hamburger in the 40pt main toolbar opening the whole menu | A File/Edit/View strip of combo boxes, which is the clearest tell |
| Tool window stripes | 40pt rails of 30pt icon buttons, names in tooltips | Rotated text labels, which is the pre-2023 look |
| Tool window headers | Mixed case, with a fold-away arrow | ALL CAPS with a close X |
| Editor tabs | File icon, hover-only close, 2pt accent underline, selected tab in the *editor's* colour | A generic tab strip with no icon and no underline |
| View mode switch | An icon toggle group at the editor's top-right | A segmented control in the title bar |
| Find bar | Docked at the *top*, one row, `Aa` / `W` / `.*` glyph chips, counter inside the field | Docked at the bottom with labelled checkboxes |
| Search Everywhere | 700pt floating surface, scope tabs, grouped results, borderless field | A narrow palette with a boxed input |
| Trees | Project root row with its location, filled folder icons tinted by role, file-type glyphs, indent guides, full-width selection | Indented text |
| Main toolbar widgets | A coloured avatar carrying the project's initial, then the Git branch | A bare project name |
| Status bar | Breadcrumbs on the left; caret, encoding, separator, file type and the writable padlock on the right | A row of statistics |
| Editor gutter | Numbers placed from the text field's own layout, so a wrapped line shows one number; the caret's line brighter, its row highlighted | One number per logical line, drifting out of step at the first wrap |
| Welcome window | A rail with product identity and navigation; large actions when empty, a searchable recent list when not | No welcome window at all |

Icons are drawn as Compose vectors in `ui/icons/IdeIcons.kt` rather than loaded as resources.
IntelliJ's own `expui` artwork is not published to Maven Central — Jewel ships without most of it —
so the alternatives were bundling artwork Quill has no licence to, or a window full of
missing-resource boxes. Drawing them on the same 16-unit grid the IDE designs on also means every
icon takes the theme tint, exactly as the IDE's SVGs do.

### The gutter is drawn from the editor's own layout

Line numbers come from the `TextLayoutResult` the text field hands back, not from counting newlines.
That distinction is the difference between a gutter and a decoration: with soft wrap on, a logical
line can occupy three visual rows, and a gutter that prints `1..n` down the side drifts out of step
with the text at the first wrapped line and stays wrong for the rest of the document. Taking the
layout means each number lands on its line's first visual row and the rows below it stay blank,
which is what the IDE does — and the caret-row highlight lands on the right row for the same reason.

**On fidelity:** this matches the New UI's documented palette, metrics and arrangement, and the
offscreen render tests show the result. It is not, and cannot be verified as, pixel-identical to a
proprietary IDE — there is no reference build here to diff against.

## Window decoration and the JetBrains Runtime

`DecoratedWindow` draws the window's own title bar through JBR-only APIs and throws on any other
JVM. The distribution bundles JBR, so that is what users get. `isJetBrainsRuntime()` detects a
stock JDK and falls back to an ordinary window with an in-window toolbar, so `./gradlew run` on a
developer's own JDK produces a working editor rather than a crash.

This interacts with the release build in a way worth remembering: the JBR API resolves its services
through generated `JBR$Xxx__Holder` classes that nothing references statically, so ProGuard removes
them unless told not to, and `com.jetbrains.JBR` then reports the API as unavailable. See
[`BUILD.md`](BUILD.md).

## Threading

| Work | Thread |
|---|---|
| Composition, state reads, text editing | Compose UI thread |
| Every engine call | `Dispatchers.Default` |
| File reads, writes and project scans | `Dispatchers.IO` |
| Code highlighting for the preview | `Dispatchers.Default`, via `flowOn` |

No engine call blocks the UI thread. Typing updates the buffer immediately; everything derived from
it arrives afterwards.
