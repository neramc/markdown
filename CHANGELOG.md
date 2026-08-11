# Changelog

All notable changes to Quill are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Markdown dialects per document.** CommonMark, GitHub Flavored Markdown, MDX and Markdoc, chosen
  from the file extension and overridable from the picker above the editor. MDX strips ESM statements
  and expression braces so components survive into the page as elements; Markdoc rewrites
  `{% tag %}` into an element the preview draws as a titled callout.
- **Fourteen inspections,** run on every keystroke: structure (heading level jumps, duplicate
  headings, more than one title), links (empty destinations, undefined references, images with no
  alternative text), footnotes (undefined, unused), code (unclosed fences, fences with no language),
  tables (rows whose cell count does not match the header) and whitespace (hard tabs, trailing
  spaces).
- **The inspection widget** above the editor, showing counts by severity, with arrows and `F2` /
  `Shift+F2` to step between findings.
- **A Problems tool window** on a new bottom dock, listing every finding in source order. Clicking a
  row selects the offending range.
- **Notifications, Terminal and Database tool windows** on the right and bottom stripes. The two with
  nothing behind them say so rather than pretending.
- **A Settings dialog** covering appearance, editor behaviour, inspections and save actions.
- **A Run/Debug Configurations dialog** for the document tasks — export to HTML, inspect, word count
  — reachable from the toolbar's run button, the Run menu and `Shift+F10`.
- **Repository files** for open-source use: `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`,
  issue and pull request templates, and `.editorconfig`.

### Changed

- **The preview renders HTML rather than the Markdown block model.** The engine converts the document
  to HTML and the preview draws that. Raw HTML in the source now renders as markup instead of
  appearing as literal text, and the preview and the exported file are the same document rather than
  two renderers with two sets of quirks.
- **The editor and preview are separate rounded panes** with a gutter between them, rather than two
  halves of one region divided by a line.
- **The package is `dev.starfect.quill`.**

### Fixed

- **CI could never have passed.** The Gradle rule in `.gitignore` was a bare `build/`, which matches
  a directory of that name at any depth — including the Kotlin package holding `CargoBuildTask`.
  Both build sources were therefore never in the repository: local builds ran on a stale jar and
  passed, while CI checked out a tree with no way to compile the Rust engine. `git status` reported
  nothing wrong throughout, because ignored files are not untracked files.
- **The shared library was staged where nothing looked for it.** The build wrote `<os>-x86_64` while
  the loader read `<os>-x64`; a clean checkout would have started with no engine. A test now asserts
  the staged resource is where the loader looks.
- **Deeply nested HTML overflowed the native stack.** The parser, the encoder and the tree's own drop
  each recursed once per nesting level, so a few thousand unclosed `<div>`s — which MDX passes
  straight through, on every keystroke — crashed the editor. Descent now stops at 256 levels.

## [1.0.0]

### Added

- Initial release: the Rust engine, the FFM bridge, the Compose Multiplatform workspace, and the
  C# / Avalonia UI Windows installer and uninstaller.
- Source editing with a line-oriented Rust lexer, so half-typed markup is coloured the way it looks
  rather than the way a structural parse insists it should be.
- Rendered preview, document outline, statistics, find and replace, Search Everywhere, HTML export
  and the welcome window.
- Dark and light themes, switched at runtime.
- `.deb`, `.rpm` and `.dmg` packaging through jlink and ProGuard; `QuillSetup.exe` and
  `QuillUninstall.exe` for Windows.

[Unreleased]: https://github.com/neramc/quill/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/neramc/quill/releases/tag/v1.0.0
