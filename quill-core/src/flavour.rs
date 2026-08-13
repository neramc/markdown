//! Markdown dialects.
//!
//! Markdown is not one language. CommonMark is the specification everything else extends; GitHub
//! adds tables, task lists and alerts; MDX embeds JSX components and ES module syntax; Markdoc adds
//! `{% tag %}` annotations. A document written in one and parsed as another either loses structure
//! or shows syntax as literal text, so the flavour is a property of the document, chosen from its
//! extension and overridable per file.
//!
//! Every dialect here is a member of the Markdown family: each one is CommonMark plus a set of
//! extensions, so each is a genuinely different *parser configuration* rather than a label. See
//! [`Flavour::extensions`] for what each one turns on — a MyST document's `$x$` is maths, a Pandoc
//! document's `H~2~O` is a subscript, and in CommonMark both are literal text.
//!
//! MDX and Markdoc additionally rewrite their non-Markdown syntax before parsing, which is the
//! honest shape of that support: their block structure is Markdown, and what they add is a component
//! vocabulary a renderer either understands or passes through.
//!
//! **AsciiDoc and Djot are deliberately absent.** Neither is a Markdown superset — they are separate
//! grammars with their own block and inline syntax, and supporting them means a second parser rather
//! than another option set on this one. Listing them here with a comrak configuration behind them
//! would parse `= Title` and `_emphasis_` as literal text and call it support.

use std::fmt;

/// One dialect's parser configuration.
///
/// A plain record rather than comrak's own options type, so the dialect table below reads as a
/// comparison: every row states what that dialect adds, and a reader can see at a glance that MyST
/// has maths and CommonMark does not.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Extensions {
    pub tables: bool,
    pub strikethrough: bool,
    pub autolink: bool,
    pub tasklist: bool,
    pub footnotes: bool,
    /// Pandoc's `^[inline note]`, which puts the note where it is referenced.
    pub inline_footnotes: bool,
    /// GitHub's `> [!NOTE]` callouts.
    pub alerts: bool,
    /// GitHub's filtering of scripting tags out of raw HTML.
    pub tagfilter: bool,
    /// `Term`/`: definition` lists, from PHP Markdown Extra and inherited by Pandoc and MultiMarkdown.
    pub description_lists: bool,
    /// `$x$` and `$$x$$` maths, as MyST, Pandoc and MultiMarkdown all use.
    pub math_dollars: bool,
    /// `\\(x\\)` LaTeX maths delimiters.
    pub math_latex: bool,
    /// `H~2~O` and `x^2^`.
    pub sub_superscript: bool,
    /// `==marked==` text.
    pub highlight: bool,
    /// `[[Wiki links]]`.
    pub wikilinks: bool,
    /// `>>>` multi-line block quotes, which MyST's `:::` directives are built on.
    pub multiline_block_quotes: bool,
    /// `:::{note}` block directives, MyST's own extension vocabulary.
    pub block_directives: bool,
    /// `__underline__` rather than bold.
    pub underline: bool,
    /// `## Heading {#custom-id}`, from PHP Markdown Extra and kept by Pandoc.
    pub header_attributes: bool,
    /// `:smile:` emoji shortcodes.
    pub shortcodes: bool,
    /// Anchor ids on headings, which every dialect that generates a table of contents needs.
    pub header_ids: bool,
}

/// A Markdown dialect.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum Flavour {
    /// The CommonMark specification, with no extensions.
    CommonMark = 0,
    /// GitHub Flavoured Markdown: tables, task lists, strikethrough, autolinks, footnotes, alerts.
    #[default]
    Gfm = 1,
    /// MDX: GFM plus JSX components and ESM `import`/`export`.
    Mdx = 2,
    /// Markdoc: GFM plus `{% tag %}` annotations.
    Markdoc = 3,
    /// MyST: CommonMark plus maths, directives and footnotes, as used by Jupyter Book and Sphinx.
    MyST = 4,
    /// Pandoc Markdown: tables, definition lists, footnotes, maths, sub- and superscript.
    Pandoc = 5,
    /// MultiMarkdown: tables, footnotes, definition lists, maths, sub- and superscript.
    MultiMarkdown = 6,
    /// PHP Markdown Extra: tables, footnotes and definition lists, and no GitHub additions.
    MarkdownExtra = 7,
}

impl Flavour {
    /// Maps a wire value back to a flavour, defaulting to GFM for anything unrecognised.
    /// The dialect with this discriminant, or `None` if it is not one this build knows.
    ///
    /// Deliberately not a fallback to [`Flavour::Gfm`]. A value this build does not recognise means
    /// the bridge was built against a newer engine than the library it loaded, and quietly serving
    /// GFM would render the document in the wrong dialect with nothing anywhere reporting why.
    /// Failing the call surfaces the mismatch where it happens.
    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::CommonMark),
            1 => Some(Self::Gfm),
            2 => Some(Self::Mdx),
            3 => Some(Self::Markdoc),
            4 => Some(Self::MyST),
            5 => Some(Self::Pandoc),
            6 => Some(Self::MultiMarkdown),
            7 => Some(Self::MarkdownExtra),
            _ => None,
        }
    }

    /// Every dialect this build knows, in the order the picker offers them.
    pub fn all() -> [Self; 8] {
        [
            Self::Gfm,
            Self::CommonMark,
            Self::MyST,
            Self::Pandoc,
            Self::MultiMarkdown,
            Self::MarkdownExtra,
            Self::Mdx,
            Self::Markdoc,
        ]
    }

    /// What this dialect adds on top of CommonMark.
    ///
    /// The one table that decides how a document parses. Written out per dialect rather than as a
    /// set of inherited defaults, because the interesting question when reading it is "what is the
    /// difference between these two", and inheritance hides exactly that.
    pub fn extensions(self) -> Extensions {
        match self {
            // The specification, and nothing else.
            Self::CommonMark => Extensions {
                header_ids: true,
                ..Extensions::default()
            },

            Self::Gfm => Extensions {
                tables: true,
                strikethrough: true,
                autolink: true,
                tasklist: true,
                footnotes: true,
                alerts: true,
                tagfilter: true,
                shortcodes: true,
                header_ids: true,
                ..Extensions::default()
            },

            // MDX and Markdoc are GFM underneath — what they add is a component vocabulary, handled
            // by `prepare` rather than by the parser. The tag filter is off for both: their whole
            // point is embedding markup, so stripping it would fight the dialect.
            Self::Mdx | Self::Markdoc => Extensions {
                tables: true,
                strikethrough: true,
                autolink: true,
                tasklist: true,
                footnotes: true,
                alerts: true,
                header_ids: true,
                ..Extensions::default()
            },

            // MyST is CommonMark plus maths and directives, for scientific writing. Its directives
            // are fenced blocks, and its roles are inline — the block structure a reader sees is
            // carried by the multi-line block quotes and the maths.
            Self::MyST => Extensions {
                tables: true,
                strikethrough: true,
                tasklist: true,
                footnotes: true,
                description_lists: true,
                math_dollars: true,
                math_latex: true,
                multiline_block_quotes: true,
                block_directives: true,
                wikilinks: true,
                header_attributes: true,
                header_ids: true,
                ..Extensions::default()
            },

            // Pandoc's Markdown is the most permissive of the family: it is the one people convert
            // *from*, so it accepts most of what the others each added.
            Self::Pandoc => Extensions {
                tables: true,
                strikethrough: true,
                autolink: true,
                tasklist: true,
                footnotes: true,
                inline_footnotes: true,
                description_lists: true,
                math_dollars: true,
                math_latex: true,
                sub_superscript: true,
                highlight: true,
                header_attributes: true,
                header_ids: true,
                ..Extensions::default()
            },

            // MultiMarkdown predates GFM and shares most of Pandoc's additions, but not its
            // autolinking or task lists.
            Self::MultiMarkdown => Extensions {
                tables: true,
                footnotes: true,
                description_lists: true,
                math_dollars: true,
                sub_superscript: true,
                header_ids: true,
                ..Extensions::default()
            },

            // PHP Markdown Extra is the original set of additions, and deliberately none of
            // GitHub's: no task lists, no strikethrough, no autolinking.
            Self::MarkdownExtra => Extensions {
                tables: true,
                footnotes: true,
                description_lists: true,
                header_attributes: true,
                header_ids: true,
                ..Extensions::default()
            },
        }
    }

    /// Picks the flavour a file extension implies.
    ///
    /// The extension is the only signal available when a file is opened, and it is a good one:
    /// `.mdx` and `.mdoc` exist precisely because those dialects are not interchangeable with plain
    /// Markdown.
    pub fn from_extension(extension: &str) -> Self {
        match extension
            .trim_start_matches('.')
            .to_ascii_lowercase()
            .as_str()
        {
            "mdx" => Self::Mdx,
            "mdoc" | "markdoc" => Self::Markdoc,
            "commonmark" | "cmark" => Self::CommonMark,
            "myst" | "mystmd" => Self::MyST,
            "pandoc" | "pmd" => Self::Pandoc,
            "mmd" | "multimarkdown" => Self::MultiMarkdown,
            "mdextra" => Self::MarkdownExtra,
            _ => Self::Gfm,
        }
    }

    /// Human-readable name, shown in the status bar's file-type widget.
    pub fn display_name(self) -> &'static str {
        match self {
            Self::CommonMark => "CommonMark",
            Self::Gfm => "GitHub Flavored Markdown",
            Self::Mdx => "MDX",
            Self::Markdoc => "Markdoc",
            Self::MyST => "MyST",
            Self::Pandoc => "Pandoc Markdown",
            Self::MultiMarkdown => "MultiMarkdown",
            Self::MarkdownExtra => "Markdown Extra",
        }
    }

    /// Whether the dialect permits raw HTML to reach the output.
    ///
    /// MDX is built on embedding components, so refusing to emit them would leave the preview
    /// permanently wrong; CommonMark and GFM keep raw HTML escaped, which is the safe default for
    /// a document that may have come from anywhere.
    pub fn allows_raw_html(self) -> bool {
        matches!(self, Self::Mdx)
    }

    /// Whether the GFM extensions apply. Kept for callers that only need the coarse answer.
    pub fn uses_gfm_extensions(self) -> bool {
        self.extensions().alerts
    }
}

impl fmt::Display for Flavour {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.display_name())
    }
}

/// One construct a dialect adds on top of Markdown, found in the source.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FlavourConstruct {
    /// Byte range in the original source.
    pub start: usize,
    pub end: usize,
    /// What the construct is.
    pub kind: ConstructKind,
    /// The construct's name: the component or tag being used.
    pub name: String,
}

/// The kinds of extra syntax the dialects contribute.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConstructKind {
    /// MDX `import` or `export` at the top level.
    EsmStatement,
    /// MDX `{ ... }` expression.
    Expression,
    /// MDX JSX element.
    JsxElement,
    /// Markdoc `{% tag %}` opening or self-closing annotation.
    MarkdocTag,
    /// Markdoc `{% /tag %}` closing annotation.
    MarkdocClosingTag,
}

/// The result of preparing a source document for parsing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Prepared {
    /// The text to hand to the Markdown parser.
    pub text: String,
    /// The dialect-specific constructs that were found.
    pub constructs: Vec<FlavourConstruct>,
}

/// Rewrites dialect-specific syntax into something the Markdown parser understands.
///
/// For CommonMark and GFM this is the identity. For the other two it is a line-oriented rewrite
/// rather than a real parse of JSX or Markdoc, and that is a deliberate limit: a full JSX parser
/// would be a large amount of code to make a *preview* marginally better, and the failure mode of
/// the line-oriented version — an unrecognised component rendered as a labelled placeholder — is one
/// a writer can see and work around.
pub fn prepare(source: &str, flavour: Flavour) -> Prepared {
    match flavour {
        // Every dialect but these two is a pure option set on the same parser, so preparation is
        // the identity: the difference is in how the text is read, not in rewriting it first.
        Flavour::Mdx => prepare_mdx(source),
        Flavour::Markdoc => prepare_markdoc(source),
        _ => Prepared {
            text: source.to_owned(),
            constructs: Vec::new(),
        },
    }
}

/// Strips MDX module syntax and records the components used.
fn prepare_mdx(source: &str) -> Prepared {
    let mut out = String::with_capacity(source.len());
    let mut constructs = Vec::new();
    let mut offset = 0usize;
    let mut in_fence = false;

    for line in source.split_inclusive('\n') {
        let trimmed = line.trim_start();
        let line_len = line.len();

        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_fence = !in_fence;
            out.push_str(line);
            offset += line_len;
            continue;
        }

        if in_fence {
            out.push_str(line);
            offset += line_len;
            continue;
        }

        // `import`/`export` are module syntax, not content. They are removed from the parsed text
        // but their line is kept as a blank so every offset after them still lines up.
        if trimmed.starts_with("import ") || trimmed.starts_with("export ") {
            constructs.push(FlavourConstruct {
                start: offset,
                end: offset + line_len,
                kind: ConstructKind::EsmStatement,
                name: trimmed
                    .split_whitespace()
                    .next()
                    .unwrap_or("import")
                    .to_owned(),
            });
            out.push('\n');
            offset += line_len;
            continue;
        }

        // `{/* ... */}` is MDX's comment form.
        if trimmed.starts_with("{/*") {
            constructs.push(FlavourConstruct {
                start: offset,
                end: offset + line_len,
                kind: ConstructKind::Expression,
                name: "comment".to_owned(),
            });
            out.push('\n');
            offset += line_len;
            continue;
        }

        if let Some(name) = jsx_element_name(trimmed) {
            constructs.push(FlavourConstruct {
                start: offset,
                end: offset + line_len,
                kind: ConstructKind::JsxElement,
                name,
            });
        }

        out.push_str(line);
        offset += line_len;
    }

    Prepared {
        text: out,
        constructs,
    }
}

/// Returns the component name when a line opens a JSX element with a capitalised tag.
///
/// Capitalisation is how MDX itself distinguishes a component from an HTML element, so `<Note>` is
/// a component and `<div>` is not.
fn jsx_element_name(line: &str) -> Option<String> {
    let rest = line.strip_prefix('<')?;
    let mut characters = rest.chars();
    let first = characters.next()?;
    if !first.is_ascii_uppercase() {
        return None;
    }

    let name: String = std::iter::once(first)
        .chain(characters.take_while(|c| c.is_alphanumeric() || *c == '.' || *c == '_'))
        .collect();
    Some(name)
}

/// Rewrites Markdoc `{% ... %}` annotations into HTML wrappers.
fn prepare_markdoc(source: &str) -> Prepared {
    let mut out = String::with_capacity(source.len());
    let mut constructs = Vec::new();
    let mut offset = 0usize;
    let mut in_fence = false;

    for line in source.split_inclusive('\n') {
        let trimmed = line.trim();
        let line_len = line.len();

        if trimmed.starts_with("```") || trimmed.starts_with("~~~") {
            in_fence = !in_fence;
            out.push_str(line);
            offset += line_len;
            continue;
        }

        if in_fence {
            out.push_str(line);
            offset += line_len;
            continue;
        }

        if let Some(inner) = trimmed
            .strip_prefix("{%")
            .and_then(|rest| rest.strip_suffix("%}"))
        {
            let inner = inner.trim();

            if let Some(name) = inner.strip_prefix('/') {
                // Closing annotation.
                let name = name.trim().to_owned();
                constructs.push(FlavourConstruct {
                    start: offset,
                    end: offset + line_len,
                    kind: ConstructKind::MarkdocClosingTag,
                    name: name.clone(),
                });
                out.push_str("</div>\n\n");
            } else {
                let self_closing = inner.ends_with('/');
                let body = inner.trim_end_matches('/').trim();
                let name = body.split_whitespace().next().unwrap_or("tag").to_owned();

                constructs.push(FlavourConstruct {
                    start: offset,
                    end: offset + line_len,
                    kind: ConstructKind::MarkdocTag,
                    name: name.clone(),
                });

                if self_closing {
                    // A self-closing tag has no body, so it becomes a labelled placeholder rather
                    // than an empty wrapper nothing will ever fill.
                    out.push_str(&format!(
                        "<div class=\"markdoc-tag markdoc-{name}\" data-markdoc=\"{name}\"></div>\n\n",
                    ));
                } else {
                    out.push_str(&format!(
                        "<div class=\"markdoc-tag markdoc-{name}\" data-markdoc=\"{name}\">\n\n",
                    ));
                }
            }

            offset += line_len;
            continue;
        }

        out.push_str(line);
        offset += line_len;
    }

    Prepared {
        text: out,
        constructs,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extensions_choose_a_flavour() {
        assert_eq!(Flavour::from_extension("md"), Flavour::Gfm);
        assert_eq!(Flavour::from_extension(".markdown"), Flavour::Gfm);
        assert_eq!(Flavour::from_extension("mdx"), Flavour::Mdx);
        assert_eq!(Flavour::from_extension(".MDX"), Flavour::Mdx);
        assert_eq!(Flavour::from_extension("mdoc"), Flavour::Markdoc);
        assert_eq!(Flavour::from_extension("cmark"), Flavour::CommonMark);
        // Anything unknown is treated as ordinary Markdown rather than refused.
        assert_eq!(Flavour::from_extension("txt"), Flavour::Gfm);
    }

    #[test]
    fn wire_values_round_trip() {
        for flavour in Flavour::all() {
            assert_eq!(Flavour::from_u8(flavour as u8), Some(flavour));
        }

        // An out-of-range value is rejected rather than falling back. It arrives over an FFI
        // boundary, so it means the bridge and the library disagree about what dialects exist, and
        // quietly serving GFM would render the document wrongly with nothing reporting why.
        assert_eq!(Flavour::from_u8(Flavour::all().len() as u8), None);
        assert_eq!(Flavour::from_u8(200), None);
    }

    #[test]
    fn every_dialect_is_listed_exactly_once() {
        // `all()` drives the picker and the round-trip test above, so a dialect missing from it is
        // one the user can never select and the wire test never covers.
        let mut seen: Vec<u8> = Flavour::all().iter().map(|f| *f as u8).collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..seen.len() as u8).collect::<Vec<_>>());
    }

    #[test]
    fn the_dialects_are_actually_different_from_one_another() {
        // The point of the table: no two dialects may share an option set, or one of them is a
        // label on another rather than a dialect.
        let configurations: Vec<Extensions> = Flavour::all()
            .iter()
            .filter(|f| !matches!(f, Flavour::Mdx | Flavour::Markdoc))
            .map(|f| f.extensions())
            .collect();

        for (index, left) in configurations.iter().enumerate() {
            for right in &configurations[index + 1..] {
                assert_ne!(left, right, "two dialects parse identically");
            }
        }
    }

    #[test]
    fn the_family_resemblances_hold() {
        // Spot checks that say what each dialect is *for*, so a careless edit to the table above
        // fails here rather than in somebody's document.
        assert!(
            !Flavour::CommonMark.extensions().tables,
            "CommonMark has no tables"
        );
        assert!(
            Flavour::MyST.extensions().math_dollars,
            "MyST is for scientific writing"
        );
        assert!(
            Flavour::Pandoc.extensions().sub_superscript,
            "Pandoc has H~2~O"
        );
        assert!(
            !Flavour::MarkdownExtra.extensions().tasklist,
            "task lists are GitHub's, and predate nothing in Markdown Extra",
        );
        assert!(
            Flavour::MultiMarkdown.extensions().description_lists,
            "MultiMarkdown inherited definition lists from Markdown Extra",
        );
    }

    #[test]
    fn plain_flavours_do_not_rewrite_anything() {
        let source = "# Title\n\nimport x from 'y'\n";
        for flavour in [Flavour::CommonMark, Flavour::Gfm] {
            let prepared = prepare(source, flavour);
            assert_eq!(prepared.text, source);
            assert!(prepared.constructs.is_empty());
        }
    }

    #[test]
    fn mdx_strips_module_syntax_but_keeps_line_count() {
        let source =
            "import Note from './Note'\n\n# Title\n\n<Note kind=\"tip\">\n\nBody\n\n</Note>\n";
        let prepared = prepare(source, Flavour::Mdx);

        assert_eq!(prepared.text.lines().count(), source.lines().count());
        assert!(!prepared.text.contains("import Note"));
        assert!(prepared.text.contains("<Note kind=\"tip\">"));

        let kinds: Vec<_> = prepared.constructs.iter().map(|c| c.kind).collect();
        assert_eq!(
            kinds,
            vec![ConstructKind::EsmStatement, ConstructKind::JsxElement]
        );
        assert_eq!(prepared.constructs[1].name, "Note");
    }

    #[test]
    fn mdx_ignores_module_syntax_inside_a_fence() {
        let source = "```js\nimport x from 'y'\n```\n";
        let prepared = prepare(source, Flavour::Mdx);

        // Inside a fence the line is code being shown to the reader, not a module import.
        assert!(prepared.text.contains("import x from 'y'"));
        assert!(prepared.constructs.is_empty());
    }

    #[test]
    fn mdx_only_treats_capitalised_tags_as_components() {
        let prepared = prepare("<div>plain html</div>\n", Flavour::Mdx);
        assert!(prepared.constructs.is_empty());

        let prepared = prepare("<Callout.Warning>\n", Flavour::Mdx);
        assert_eq!(prepared.constructs[0].name, "Callout.Warning");
    }

    #[test]
    fn mdx_records_comments() {
        let prepared = prepare("{/* hidden */}\n\ntext\n", Flavour::Mdx);
        assert_eq!(prepared.constructs[0].kind, ConstructKind::Expression);
        assert!(!prepared.text.contains("hidden"));
    }

    #[test]
    fn markdoc_wraps_tags_in_divs() {
        let source = "{% callout type=\"note\" %}\nBody text\n{% /callout %}\n";
        let prepared = prepare(source, Flavour::Markdoc);

        assert!(
            prepared
                .text
                .contains("<div class=\"markdoc-tag markdoc-callout\"")
        );
        assert!(prepared.text.contains("Body text"));
        assert!(prepared.text.contains("</div>"));

        assert_eq!(prepared.constructs.len(), 2);
        assert_eq!(prepared.constructs[0].kind, ConstructKind::MarkdocTag);
        assert_eq!(prepared.constructs[0].name, "callout");
        assert_eq!(
            prepared.constructs[1].kind,
            ConstructKind::MarkdocClosingTag
        );
    }

    #[test]
    fn markdoc_self_closing_tags_become_placeholders() {
        let prepared = prepare("{% partial file=\"x.md\" /%}\n", Flavour::Markdoc);

        assert!(prepared.text.contains("data-markdoc=\"partial\""));
        // A self-closing tag opens and closes in one element; it must not leave an unbalanced div.
        assert_eq!(prepared.text.matches("<div").count(), 1);
        assert_eq!(prepared.text.matches("</div>").count(), 1);
        assert_eq!(prepared.constructs.len(), 1);
    }

    #[test]
    fn markdoc_leaves_fenced_content_alone() {
        let prepared = prepare("```\n{% callout %}\n```\n", Flavour::Markdoc);
        assert!(prepared.text.contains("{% callout %}"));
        assert!(prepared.constructs.is_empty());
    }

    #[test]
    fn raw_html_is_only_allowed_where_the_dialect_needs_it() {
        assert!(Flavour::Mdx.allows_raw_html());
        assert!(!Flavour::Gfm.allows_raw_html());
        assert!(!Flavour::CommonMark.allows_raw_html());
    }

    #[test]
    fn commonmark_disables_the_gfm_extensions() {
        assert!(!Flavour::CommonMark.uses_gfm_extensions());
        assert!(Flavour::Gfm.uses_gfm_extensions());
        assert!(Flavour::Mdx.uses_gfm_extensions());
        assert!(Flavour::Markdoc.uses_gfm_extensions());
    }
}
