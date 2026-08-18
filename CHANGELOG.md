# Changelog

Notable changes, newest first. Versions follow [semantic versioning](https://semver.org).

The sections here are the source of the release notes on GitHub: the publish workflow extracts the
section matching the tag it was triggered by, so a release whose notes are wrong is a changelog
whose notes are wrong, and there is only one place to fix it.

## 1.3.0

### Nothing irreversible happens quietly

Closing a modified document threw the work away between one click and the next frame. The tab's
close button, `Ctrl+W`, Close All and the window's X all called straight through to the close.

They now ask — once for a whole batch rather than once per tab, because "Close All" over twelve tabs
with four modified is one decision to the person making it, and four dialogs in a row is how a
confirmation becomes something you click through without reading. The names are listed rather than
counted: a number is something you have to trust, and the names are what you actually check before
pressing Discard. Saving waits for the bytes to reach disk before the tab goes, since the tab
disappearing is the only signal the writer gets that it is safe to quit.

Quitting asks the same way. So does **Reload from Disk**, which is the one action in the editor that
destroys work and produces nothing in return — and where Cancel is the *default* answer, unlike
everywhere else, because here the answer that keeps the work is doing nothing at all.

**Replace All could not be undone.** It wrote the replaced text straight into the document instead of
going through the edit path, so the undo history still described the text from before it ran and
`Ctrl+Z` either did nothing or jumped past the replacement to an older edit. A bulk rewrite driven by
a two-word query is the worst thing in the editor to leave unrecoverable. It is one step now, and it
reports how many occurrences it changed — three replacements and three hundred look identical when
the only feedback is the document redrawing.

### Long work is visible while it runs

Opening a project, exporting and searching a project all walked the disk with nothing on screen to
say so. On a large tree that is several seconds of a window that looks hung.

The status bar now carries whatever is running, with a progress bar where the total is known and an
indeterminate one where it is not. It waits 200 ms before appearing, so a fast scan does not flash a
bar at you, and stays at least 400 ms once shown, so it does not flicker. More than one running
collapses into a popup listing them, each with a stop button.

### Back and forward

`Ctrl+Alt+Left` and `Ctrl+Alt+Right`, over the places you have been, with the toolbar arrows the IDE
puts beside the project widget.

The history is a single list with a cursor on it rather than two stacks — a stack pair drops the
forward side as soon as anything else happens, which is what "the forward arrow never works"
describes. Recording is coarse on purpose: a jump landing within 25 lines of the current entry
replaces it instead of appending, so paging through search hits leaves one entry per region and not
one per hit. Go to Line, the outline, a problem, a search result and switching tabs record; typing
and arrow keys do not.

Closing a tab does not erase where you have been. The place keeps its path and loses only its
document id, and Back reopens the file to get there.

### Three shortcuts that did not work

Writing the keyboard reference for the new Learn page turned them up, and a test that presses every
binding the reference lists now keeps them honest.

- **`Alt+Shift+Up` / `Alt+Shift+Down`** (move line) sat inside the block that only runs with Ctrl
  held, so the real binding was `Ctrl+Alt+Shift+arrow` and the documented one did nothing.
- **`Ctrl+G`** stepped the find matches, which left **Go to Line** with no binding at all while a
  menu entry claimed one. `Ctrl+G` is Go to Line now and `F3` / `Shift+F3` step the matches, as in
  the IDE.
- **`Ctrl+Shift+T`** was both the task-list toggle and the theme toggle. The task list is bound
  first and returns, so with a document open — which is always — the theme binding could never fire.

### A toolbar and a welcome screen that match the IDE

The toolbar gains the navigation pair, a run-configuration widget that names its configuration (or
says "Add Configuration", which is what to press rather than an empty dropdown), an overflow menu,
and a rule separating what acts on the document from what acts on the application.

The welcome screen gains a Learn page listing the whole keyboard, and Configure and Help menus
pinned to the bottom of the rail. Those needed every dialog moved into one place first: they set
state that only the main window rendered, so Settings from the welcome screen would have opened
nothing. Its header also folds — above 560 points the actions are spelled out beside the search
field, below it they collapse into an overflow so the field keeps its width.

### Controls that still did nothing

Four more places lit up under the pointer and did not keep the promise.

The **breadcrumbs** were decoration except for the heading crumb — four broken promises along the
bottom of the window, per document. Every crumb now reveals its target in the project view. **"Show
in Project View"** only opened the panel, which on a project of any depth leaves the file exactly as
hidden as it was; it expands every directory down to the target now. The **tool window title
chevron** is the IDE's view switcher, so it is one — and it only appears where the dock has
somewhere else to go. The **project root row** had a hover fill and an empty handler, and now has
neither.

The Project panel also gained **Refresh**, which it needed badly: the tree was read once when the
project opened and never again, so a file created in a terminal — or by Quill's own export — was
invisible until a restart.

### Motion

Tool windows grow out of the edge they dock to and collapse back into it. Dialogs and the search
palettes arrive with a fade and a 2% scale. Editor tabs cross-fade their fill, the accent bar fades
between them instead of jumping, and closing one slides the rest along. The status strip cross-fades
between the breadcrumbs and a message.

Every duration is asserted inside a band, and a render test drives a panel through a visibility
change until the scene stops asking to be drawn — an animation that never settles is a repaint loop,
which on the desktop is a pinned core for as long as the window is open.

### Save As

`Ctrl+Shift+S`. Saving a document that had never been on disk previously reported that it had
nowhere to write and stopped there, because there was no file picker anywhere in the application.

### Typing is between three and four times faster

A keystroke in a 2000-line document cost 1.6 seconds. The parser was not the reason: a bare Compose
text field with none of Quill around it costs 40 ms at 500 lines, and Quill's editor cost 210. The
difference was syntax highlighting — the controller asked the engine for the whole document's style
spans and handed every one of them to the text field, which turns each into a run it has to shape
separately. Two thousand runs on a document nobody was looking at fifty lines of.

Highlighting now follows the viewport, which is what the engine's line range was always for.

| document | before | after |
|---|---|---|
| 100 lines | 54 ms | 34 ms |
| 500 lines | 211 ms | 114 ms |
| 2000 lines | 1579 ms | 455 ms |

Closing a tab also stopped freezing the window. It waited for the document's parse on the UI thread,
so shutting six tabs held the window for seconds.

### Undo and redo

There was no Ctrl+Z binding, so undo fell through to the one Compose's text field carries — which
belongs to the text field rather than the document and was thrown away by switching tabs. There was
no redo at all.

The history belongs to the document now, and the feature is the grouping rather than the stack: a
pause starts a new step, typing and deleting never merge, and whitespace closes a group so undo
steps back a word at a time. A paste or a reformat is one step however large.

### A file changed outside Quill is no longer overwritten

Saving was an unconditional write, so a `git checkout`, a formatter or a second editor touching a
file while a document sat open destroyed that work with no message.

Each document remembers what was on disk when Quill last agreed with it. A save that would overwrite
a changed file is refused and says so. On returning to the window an unmodified buffer reloads
silently; an edited one is flagged in the status bar with a click to discard and reload. Quill does
not choose which version wins.

### Controls that do something

Seven controls had no action behind them while advertising otherwise. The tab strip's ⋮ now opens
the menu it looks like it should — close, close others, close to the right, close all, copy path.
The project widget opens a project menu. The status bar's caret position opens **Go to Line**, which
had to be written, because the tooltip had been promising it.

The rest were never controls: the branch name, the encoding, the line ending, the file type and the
padlock report things Quill has no action for, and they have stopped rendering as buttons.

### Icons

Windows and macOS shipped with no Quill icon at all — jpackage takes a different container per
platform and only the Linux PNG was ever wired up. The installer's icon was stock clipart of a
cardboard box.

There is now one family: the application, the installer and the `.md` document all carry the same
feather, rendered into PNG, multi-size ICO and ICNS from the vectors by `tools/render-icons.sh`.
Below 32 pixels the installer switches to a simpler drawing, because detail that reads at 256 pixels
becomes a smudge at 16.

## 1.2.0

### The uninstaller is gone, and Quill removes itself

There used to be a second Windows executable whose only job was to delete files. It was a hundred
and three megabytes of self-contained .NET, it shipped in every release, and it then sat in the
install folder for the lifetime of the installation — larger than the editor it removed.

Quill now removes itself, from **Help → Uninstall Quill…** or from Apps & features, which runs
`Quill.exe --uninstall`. Beyond deleting 103 MB from the release this removes a class of failure:
the remover cannot be missing, be the wrong version, or disagree with what it is removing, because
it *is* what it is removing.

It is careful about the things an uninstaller can get wrong. A file type another editor has claimed
since installation is left with that editor; one still registered to Quill is handed back to
whatever held it before. Directories are removed only when empty, so somebody who installed into a
folder holding their own files keeps those files. Nothing outside the install root is ever touched,
whatever the manifest says.

### Updating in place

**Help → Check for Updates…** asks the releases feed what the newest version is, and what it can do
about the answer depends on who owns the installation. A portable unpack or a per-user Windows
install is replaced in place: the new version is downloaded, verified, unpacked beside the old one,
and Quill closes and starts again as the new version. An installation a package manager owns —
`/opt/quill` from a `.deb`, an `.app` in `/Applications` — is handed its own installer instead,
because writing over those needs root and would leave the package manager describing files that are
no longer there.

Downloads are checked against the release's `SHA256SUMS` before anything is unpacked, and a file
that does not match is deleted rather than kept. The swap moves the old installation aside before
moving the new one in, so a failure leaves something to go back to rather than nothing.

### A third smaller

An installed Quill was 157 MB. It is now 112 MB, and almost none of either number was ever Quill:
the bundled Java runtime shipped uncompressed, Skia and the JVM shipped with full symbol tables, and
the runtime carried forty-three fonts of which Quill opens nine.

- The bundled runtime is compressed — `lib/modules` from 55 MB to 25 MB. It costs about twenty
  milliseconds of class loading at startup, which is the trade being made deliberately, and it does
  not make the *download* smaller: gzip was already doing that work on the way to disk. What changes
  is what the machine keeps.
- Skia, the JVM and the Rust engine are stripped of symbols nothing in a shipped application reads.
- The thirty-four fonts nothing references are dropped.

`-J-Dquill.startup.trace=true` prints time to first frame, if you want to see the cost.

### Also

- The Windows installer registered `bin\Quill.exe`. jpackage puts the launcher at the root of a
  Windows application image — only Linux uses `bin/` — so both shortcuts, the `.md` file handler,
  the icon in Apps & features and the uninstall command all pointed at a file that was never there
  while setup reported success. Setup now refuses a payload that does not contain the launcher
  rather than registering four things that point at nothing.

## 1.1.0

### Faster to start

The engine's shared library is five and a half megabytes and used to be inflated out of the jar and
written to a temporary file on every single launch, then deleted at exit so the next launch could do
it again. It is now extracted once into the user's cache directory and simply opened thereafter.
Loading the engine and bringing up the window also no longer wait for each other — they are
independent, and doing them in sequence cost the sum of two waits where the longer would do.

Settings are read before the window opens rather than after the first frame, so it no longer appears
in the default theme and then corrects itself. The recent-projects list moved the other way: it
checks up to thirty directories for still existing, which the welcome window now paints without
waiting for.

### Settings

Every setting is declared in one place, and the settings file, the dialog, the search and the VS Code
import are all derived from that declaration. The dialog gained a search box that covers every
setting by name, by key, or by what it does.

Settings written by 1.0.0 are still read, so nothing is lost on upgrade.

### Import from VS Code

Settings → Import finds VS Code, Insiders, VSCodium, Cursor and Windsurf, and copies across the
settings that mean the same thing in both editors — font size, tab width, word wrap, line numbers,
rulers, the save behaviours. Anything without a fair equivalent is listed rather than guessed at.

Language-scoped blocks win over the global value: somebody who wrote `"[markdown]": { … }` has said
something specific about editing Markdown, which is the only thing Quill does. The file is parsed as
the JSON-with-comments that VS Code actually writes, so a settings file with comments and a trailing
comma reads correctly rather than failing.

### Editing

- **Sticky headings.** The headings you are underneath stay pinned above the text as it scrolls, so
  a long document always says which section you are in.
- **Minimap.** The whole document in miniature down the right edge; click or drag it to move.
- **Closing pairs and wrapping.** Typing `(` gives `()`. Selecting a word and typing a backtick
  gives `` `word` ``. Asterisks and underscores wrap a selection but never close themselves, because
  `* ` at the start of a line is a bullet.
- **Save after a pause.** A modified file writes itself once you stop typing, if you ask it to.

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

Only the glyphs the document draws are embedded, and every stream is compressed, so a page of Korean
is around forty kilobytes rather than the twelve megabytes an embedded CJK font weighs. Fonts stored
as collections — which is how macOS ships most of its CJK families — are unpacked first, since a PDF
reader rejects a font file holding more than one font. The file carries a `ToUnicode` map, so text
selected in a reader can be copied and searched rather than coming out as glyph numbers.

### The workspace

- Fourteen inspections on every keystroke, with a Problems tool window and `F2` to step through.
- Tool windows on three docks, run configurations, settings, breadcrumbs, dark and light themes.
- Search Everywhere over every command, with subsequence matching.

### Platforms

Linux (x64 and arm64), macOS (Intel and Apple Silicon) and Windows (x64), each with a native
package and a portable archive that needs no installer.
