# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org).

The sections here are the source of the release notes on GitHub: the publish workflow extracts the
section matching the tag it was triggered by, so a release whose notes are wrong is a changelog
whose notes are wrong, and there is only one place to fix it.

## 1.0.0

The first release.

### Writing

- Two panes, one document: the source and the page it becomes, side by side or either alone.
- Syntax highlighting from a line-oriented lexer rather than a structural parse, so half-typed
  markup colours the way it looks instead of the way it would parse.
- The preview is rendered through HTML, which is what makes raw HTML in a document render as markup
  and what keeps the preview and the exported file from being two renderers with two sets of quirks.
- Smart Enter continues lists, tasks and quotes, and clears an empty marker rather than adding
  another.
- Bold, italic, code, strikethrough, links, headings, bullets, tasks, quotes, line moves, line
  duplication and table formatting, each on the binding it has in every other editor.
- Find and replace with literal, whole-word and regular-expression modes, run in the engine.

### Eight Markdown dialects

CommonMark, GitHub Flavored Markdown, MDX, Markdoc, MyST, Pandoc Markdown, MultiMarkdown and
Markdown Extra — chosen per document from its extension and overridable from the picker.

These are parser configurations rather than labels. `$x^2$` is a formula in MyST and three
characters in CommonMark; `H~2~O` is a subscript in Pandoc and a pair of tildes in GFM; a definition
list parses in Markdown Extra and a task list does not.

AsciiDoc and Djot are deliberately absent: neither is a Markdown superset, and listing them behind a
Markdown parser would render `= Title` as literal text and call it support.

### Paste and drop from anywhere

The clipboard is never one thing. Copying a passage out of a web page puts plain text *and* an HTML
fragment on it, and the plain one — the flavour a naive paste takes — is the one the structure has
already been thrown away from. Quill converts the HTML, so a paste from Word, Notion, Google Docs,
Confluence or a rendered README arrives as source somebody would have typed.

- Google Docs' `font-weight:700` bold and its document-wide `<b style="font-weight:normal">` wrapper
  are both handled, as are Word's `mso-list` paragraphs, which are a nested list written as a run of
  paragraphs with the bullet in a span marked "ignore".
- Escaping is narrow on purpose: `snake_case` and `well-known` come through untouched, because
  over-escaping looks like a broken tool.
- An image on the clipboard is filed beside the document and linked; a file dropped anywhere in the
  window takes the same path, and one from outside the project is copied in first.

### Finding things

- **Every Markdown feature, searchable** on `Ctrl/Cmd+K`, or by typing `/` at the start of a line.
  Each entry shows the syntax it writes, so the list teaches itself out of a job. The `/` trigger
  only fires at the start of a line, because `and/or` and `src/main` are prose.
- **Five project searches** on one dialog: file names, document text, regular expressions, recently
  modified, and the TODO notes scattered through every file that has one. Build output and version
  control are never searched, and a truncated result set says so.

### Modes

- **Vim mode** — a parser rather than a key map: motions, operators, counts, registers, visual and
  visual-line modes, `u`/`Ctrl+R`, and `:w`/`:q`/`:noh`. Insert mode is deliberately left alone so
  input methods, dead keys and the clipboard keep working.
- **Focus Mode** — one centred column, every paragraph but the current one dimmed, nothing else on
  screen.
- **Reading mode** on `Ctrl+Shift+M`, which remembers the arrangement it came from.

### Direct manipulation

- Ticking a checkbox in the preview edits the source.
- Dragging a row in the Structure panel moves the whole section, subsections included.
- A table of contents between `<!-- toc -->` and `<!-- /toc -->` keeps itself current. A document
  without the markers is never touched.

### Export and conversion

HTML, PDF, Word, EPUB, Confluence, Notion and a GitHub README.

The last three are translations rather than renderings — the output is still a document, which the
target system then owns — so each produces that system's own constructs: a Confluence code macro
rather than a `<pre>`, Notion's three heading levels rather than six. Every lossy step loses the
syntax and keeps the content.

The PDF exporter embeds a font. A PDF can use fourteen fonts without embedding anything and all
fourteen are Latin, so the short version of this feature writes a document in which every Hangul
syllable is an empty box. Quill scans the document's own text, finds a font on the machine that
covers it, and embeds that as a CID font; line breaking knows that Korean and Japanese are written
without spaces. When no font covers the text the export still happens and reports exactly which
characters are missing.

### The workspace

- Fourteen inspections on every keystroke, with a Problems tool window and `F2` to step through.
- Tool windows on three docks, run configurations, settings, breadcrumbs, dark and light themes.
- Search Everywhere over every command, with subsequence matching.

### Platforms

Linux (x64 and arm64), macOS (Intel and Apple Silicon) and Windows (x64), each with a native
package and a portable archive that needs no installer.
