//! Converting a document into the dialect another tool speaks.
//!
//! Exporting to HTML is a rendering: the output is a different language and nobody expects to edit
//! it. Exporting to Confluence, Notion or a GitHub README is not — the output is *still a document*,
//! which somebody will paste into a system that then owns it. So each of these is a translation
//! rather than a rendering, and the thing that makes one good is knowing what the target cannot do:
//!
//! * **Confluence** stores pages as its own XHTML, in which a code block is a macro, a callout is a
//!   macro, and a task list is a first-class element rather than a checkbox glyph. Sending it
//!   ordinary HTML produces a page that looks approximately right and cannot be edited with any of
//!   Confluence's own tools.
//! * **Notion** imports Markdown, but its own model has three heading levels, no footnotes, no
//!   definition lists and no raw HTML. Markdown it cannot represent does not fail to import — it
//!   imports as literal text in the middle of the page, which is worse.
//! * **A GitHub README** is GFM and nothing else. Every dialect-specific construct this editor can
//!   parse — `H~2~O`, `==marked==`, `Term`/`: definition`, `[[wiki links]]` — renders on GitHub as
//!   the characters you typed.
//!
//! Each conversion therefore *loses* something, and the rule followed throughout is that a lossy
//! conversion must lose the syntax and keep the content. A footnote becomes a parenthetical, not
//! nothing; a definition list becomes a bold term and an indented line, not nothing.

use comrak::nodes::{AstNode, ListDelimType, ListType, NodeValue, TableAlignment};
use comrak::{Arena, parse_document};

use crate::flavour::Flavour;

/// What a document is being converted into.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Target {
    /// Confluence storage format: XHTML with Confluence's own macros.
    Confluence = 0,
    /// Markdown restricted to what Notion's importer understands.
    Notion = 1,
    /// GitHub Flavoured Markdown, with every other dialect's syntax translated into it.
    GitHubReadme = 2,
}

impl Target {
    pub fn from_u8(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Confluence),
            1 => Some(Self::Notion),
            2 => Some(Self::GitHubReadme),
            _ => None,
        }
    }

    pub fn display_name(self) -> &'static str {
        match self {
            Self::Confluence => "Confluence",
            Self::Notion => "Notion",
            Self::GitHubReadme => "GitHub README",
        }
    }

    /// The extension the result should be saved with.
    pub fn extension(self) -> &'static str {
        match self {
            Self::Confluence => "xml",
            Self::Notion | Self::GitHubReadme => "md",
        }
    }
}

/// Converts a document, read in `flavour`, into `target`'s own form.
pub fn convert(source: &str, flavour: Flavour, target: Target) -> String {
    let prepared = crate::flavour::prepare(source, flavour);
    let arena = Arena::new();
    let options = crate::parser::options_for(flavour);
    let root = parse_document(&arena, &prepared.text, &options);

    match target {
        Target::Confluence => {
            let mut writer = Confluence::default();
            writer.blocks(root);
            writer.out
        }
        Target::Notion => markdown(root, Dialect::Notion),
        Target::GitHubReadme => markdown(root, Dialect::GitHub),
    }
}

// ------------------------------------------------------------------------------------ Confluence

/// Confluence storage format.
#[derive(Default)]
struct Confluence {
    out: String,
    /// Footnote definitions, collected so they can be written as a section at the end the way
    /// Confluence pages conventionally do it.
    footnotes: Vec<(String, String)>,
}

impl Confluence {
    fn blocks<'a>(&mut self, node: &'a AstNode<'a>) {
        for child in node.children() {
            self.block(child);
        }
        if !self.footnotes.is_empty() {
            self.out.push_str("<hr />\n");
            let notes = std::mem::take(&mut self.footnotes);
            for (name, body) in notes {
                self.out
                    .push_str(&format!("<p><sup>{}</sup> {}</p>\n", escape(&name), body));
            }
        }
    }

    fn block<'a>(&mut self, node: &'a AstNode<'a>) {
        let value = node.data.borrow().value.clone();

        match value {
            NodeValue::Document => self.blocks(node),

            NodeValue::Paragraph => {
                self.out.push_str("<p>");
                self.inlines(node);
                self.out.push_str("</p>\n");
            }

            NodeValue::Heading(heading) => {
                let level = heading.level.clamp(1, 6);
                self.out.push_str(&format!("<h{level}>"));
                self.inlines(node);
                self.out.push_str(&format!("</h{level}>\n"));
            }

            NodeValue::ThematicBreak => self.out.push_str("<hr />\n"),

            // A code block is a macro in Confluence, not a `<pre>`. The difference is whether the
            // page's own editor can later change the language on it.
            NodeValue::CodeBlock(code) => {
                let language = code
                    .info
                    .split(|c: char| c == ',' || c.is_whitespace())
                    .next()
                    .unwrap_or("");
                self.out
                    .push_str("<ac:structured-macro ac:name=\"code\" ac:schema-version=\"1\">");
                if !language.is_empty() {
                    self.out.push_str(&format!(
                        "<ac:parameter ac:name=\"language\">{}</ac:parameter>",
                        escape(language)
                    ));
                }
                // CDATA, because source is exactly the thing that contains angle brackets. The one
                // sequence CDATA cannot carry is its own terminator, so it is split across two
                // sections rather than escaped -- escaping it would change the code.
                self.out.push_str(&format!(
                    "<ac:plain-text-body><![CDATA[{}]]></ac:plain-text-body>",
                    code.literal.replace("]]>", "]]]]><![CDATA[>")
                ));
                self.out.push_str("</ac:structured-macro>\n");
            }

            // A GitHub alert is a Confluence macro of the matching kind, which is what makes it
            // editable rather than a quotation somebody has to rebuild.
            NodeValue::Alert(alert) => {
                let macro_name = match alert.alert_type {
                    comrak::nodes::AlertType::Note => "info",
                    comrak::nodes::AlertType::Tip => "tip",
                    comrak::nodes::AlertType::Important => "note",
                    comrak::nodes::AlertType::Warning => "warning",
                    comrak::nodes::AlertType::Caution => "warning",
                };
                self.out.push_str(&format!(
                    "<ac:structured-macro ac:name=\"{macro_name}\" ac:schema-version=\"1\">"
                ));
                if let Some(title) = &alert.title {
                    self.out.push_str(&format!(
                        "<ac:parameter ac:name=\"title\">{}</ac:parameter>",
                        escape(title)
                    ));
                }
                self.out.push_str("<ac:rich-text-body>");
                for child in node.children() {
                    self.block(child);
                }
                self.out
                    .push_str("</ac:rich-text-body></ac:structured-macro>\n");
            }

            NodeValue::BlockQuote | NodeValue::MultilineBlockQuote(_) => {
                self.out.push_str("<blockquote>");
                for child in node.children() {
                    self.block(child);
                }
                self.out.push_str("</blockquote>\n");
            }

            NodeValue::List(list) => {
                // A list of task items is a Confluence task list, which is a different element
                // entirely -- and the one people actually tick.
                if node
                    .children()
                    .any(|child| matches!(child.data.borrow().value, NodeValue::TaskItem(_)))
                {
                    self.out.push_str("<ac:task-list>");
                    for child in node.children() {
                        self.task(child);
                    }
                    self.out.push_str("</ac:task-list>\n");
                    return;
                }

                let tag = match list.list_type {
                    ListType::Ordered => "ol",
                    ListType::Bullet => "ul",
                };
                self.out.push_str(&format!("<{tag}>"));
                for child in node.children() {
                    self.out.push_str("<li>");
                    self.item_content(child);
                    self.out.push_str("</li>");
                }
                self.out.push_str(&format!("</{tag}>\n"));
            }

            NodeValue::Item(_) | NodeValue::TaskItem(_) => {
                self.out.push_str("<li>");
                self.item_content(node);
                self.out.push_str("</li>");
            }

            NodeValue::Table(_) => {
                self.out.push_str("<table><tbody>");
                for child in node.children() {
                    self.block(child);
                }
                self.out.push_str("</tbody></table>\n");
            }

            NodeValue::TableRow(is_header) => {
                self.out.push_str("<tr>");
                let cell_tag = if is_header { "th" } else { "td" };
                for child in node.children() {
                    self.out.push_str(&format!("<{cell_tag}>"));
                    self.inlines(child);
                    self.out.push_str(&format!("</{cell_tag}>"));
                }
                self.out.push_str("</tr>");
            }

            NodeValue::DescriptionList => {
                self.out.push_str("<dl>");
                for child in node.children() {
                    self.block(child);
                }
                self.out.push_str("</dl>\n");
            }

            NodeValue::DescriptionItem(_) => {
                for child in node.children() {
                    self.block(child);
                }
            }

            NodeValue::DescriptionTerm => {
                self.out.push_str("<dt>");
                self.inlines(node);
                self.out.push_str("</dt>");
            }

            NodeValue::DescriptionDetails => {
                self.out.push_str("<dd>");
                for child in node.children() {
                    self.block(child);
                }
                self.out.push_str("</dd>");
            }

            NodeValue::FootnoteDefinition(definition) => {
                let mut body = Confluence::default();
                for child in node.children() {
                    body.block(child);
                }
                self.footnotes.push((definition.name.clone(), body.out));
            }

            // Front matter is metadata about the file, not content of the page.
            NodeValue::FrontMatter(_) => {}

            // Raw HTML is passed through: Confluence storage format is XHTML, so well-formed markup
            // is already in the target language.
            NodeValue::HtmlBlock(html) => self.out.push_str(&html.literal),

            _ => {
                for child in node.children() {
                    self.block(child);
                }
            }
        }
    }

    /// A list item's content, without the paragraph wrapper a single-paragraph item would get.
    fn item_content<'a>(&mut self, node: &'a AstNode<'a>) {
        let children: Vec<_> = node.children().collect();
        if children.len() == 1 && matches!(children[0].data.borrow().value, NodeValue::Paragraph) {
            self.inlines(children[0]);
            return;
        }
        for child in children {
            self.block(child);
        }
    }

    fn task<'a>(&mut self, node: &'a AstNode<'a>) {
        let status = match &node.data.borrow().value {
            NodeValue::TaskItem(task) if task.symbol.is_some() => "complete",
            _ => "incomplete",
        };
        self.out.push_str(&format!(
            "<ac:task><ac:task-status>{status}</ac:task-status><ac:task-body>"
        ));
        self.item_content(node);
        self.out.push_str("</ac:task-body></ac:task>");
    }

    fn inlines<'a>(&mut self, node: &'a AstNode<'a>) {
        for child in node.children() {
            self.inline(child);
        }
    }

    fn inline<'a>(&mut self, node: &'a AstNode<'a>) {
        let value = node.data.borrow().value.clone();

        match value {
            NodeValue::Text(text) => self.out.push_str(&escape(&text)),
            NodeValue::Code(code) => self
                .out
                .push_str(&format!("<code>{}</code>", escape(&code.literal))),
            NodeValue::Strong => self.wrap(node, "strong"),
            NodeValue::Emph => self.wrap(node, "em"),
            NodeValue::Strikethrough => self.wrap(node, "s"),
            NodeValue::Underline => self.wrap(node, "u"),
            NodeValue::Superscript => self.wrap(node, "sup"),
            NodeValue::Subscript => self.wrap(node, "sub"),
            NodeValue::Highlight => self.wrap(node, "mark"),

            NodeValue::SoftBreak => self.out.push(' '),
            NodeValue::LineBreak => self.out.push_str("<br />"),

            NodeValue::Link(link) => {
                self.out
                    .push_str(&format!("<a href=\"{}\">", escape(&link.url)));
                self.inlines(node);
                self.out.push_str("</a>");
            }

            NodeValue::WikiLink(link) => {
                self.out
                    .push_str(&format!("<ac:link><ri:page ri:content-title=\"{}\" /></ac:link>", escape(&link.url)));
            }

            // Confluence attaches images rather than linking them, and an external one is a
            // different element from an attachment.
            NodeValue::Image(link) => {
                let url = escape(&link.url);
                if link.url.starts_with("http://") || link.url.starts_with("https://") {
                    self.out
                        .push_str(&format!("<ac:image><ri:url ri:value=\"{url}\" /></ac:image>"));
                } else {
                    let name = link.url.rsplit('/').next().unwrap_or(&link.url);
                    self.out.push_str(&format!(
                        "<ac:image><ri:attachment ri:filename=\"{}\" /></ac:image>",
                        escape(name)
                    ));
                }
            }

            NodeValue::FootnoteReference(reference) => self
                .out
                .push_str(&format!("<sup>{}</sup>", escape(&reference.name))),

            NodeValue::Math(math) => self
                .out
                .push_str(&format!("<code>{}</code>", escape(&math.literal))),

            NodeValue::HtmlInline(html) => self.out.push_str(&html),

            _ => self.inlines(node),
        }
    }

    fn wrap<'a>(&mut self, node: &'a AstNode<'a>, tag: &str) {
        self.out.push_str(&format!("<{tag}>"));
        self.inlines(node);
        self.out.push_str(&format!("</{tag}>"));
    }
}

/// XML escaping. Confluence storage format is XML, so an unescaped ampersand is a parse error there
/// rather than a rendering quirk.
fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for character in text.chars() {
        match character {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(character),
        }
    }
    out
}

// -------------------------------------------------------------------------------------- Markdown

/// Which Markdown the writer is producing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum Dialect {
    /// What Notion's importer understands.
    Notion,
    /// GFM, as GitHub renders it. The default because it is the widest of the two.
    #[default]
    GitHub,
}

impl Dialect {
    /// Notion has three heading levels; GitHub has six.
    fn max_heading(self) -> u8 {
        match self {
            Self::Notion => 3,
            Self::GitHub => 6,
        }
    }

    /// Whether raw HTML survives. Notion's importer shows it as text.
    fn keeps_html(self) -> bool {
        matches!(self, Self::GitHub)
    }
}

/// Writes a document back out as Markdown in [Dialect]'s subset.
fn markdown<'a>(root: &'a AstNode<'a>, dialect: Dialect) -> String {
    let mut writer = Markdown {
        dialect,
        ..Markdown::default()
    };
    writer.blocks(root, "");

    let mut out = writer.out.trim_end().to_owned();
    if !writer.footnotes.is_empty() {
        out.push_str("\n\n---\n");
        for (name, body) in &writer.footnotes {
            out.push_str(&format!("\n{name}. {body}\n"));
        }
        out = out.trim_end().to_owned();
    }
    out.push('\n');
    out
}

#[derive(Default)]
struct Markdown {
    out: String,
    dialect: Dialect,
    /// Whether blocks are separated by one newline rather than a blank line.
    ///
    /// A tight list is one whose items have no blank lines between them, and a nested list inside
    /// a tight item has to stay tight with it — a blank line there makes the *whole* list loose,
    /// which Markdown renders with a paragraph's worth of space between every item.
    tight: bool,
    /// Footnote bodies, gathered for the numbered section at the end.
    footnotes: Vec<(String, String)>,
}

impl Markdown {
    fn blocks<'a>(&mut self, node: &'a AstNode<'a>, prefix: &str) {
        for child in node.children() {
            self.block(child, prefix);
        }
    }

    /// Appends a block, prefixing every line of it and separating it from what came before.
    fn push_block(&mut self, prefix: &str, body: &str) {
        if body.trim().is_empty() {
            return;
        }
        if !self.out.is_empty() {
            if !self.out.ends_with('\n') {
                self.out.push('\n');
            }
            if !self.tight && !self.out.ends_with("\n\n") {
                self.out.push('\n');
            }
        }
        for (index, line) in body.trim_end().split('\n').enumerate() {
            if index > 0 {
                self.out.push('\n');
            }
            if line.is_empty() {
                self.out.push_str(prefix.trim_end());
            } else {
                self.out.push_str(prefix);
                self.out.push_str(line);
            }
        }
        self.out.push('\n');
    }

    fn block<'a>(&mut self, node: &'a AstNode<'a>, prefix: &str) {
        let value = node.data.borrow().value.clone();

        match value {
            NodeValue::Document => self.blocks(node, prefix),

            NodeValue::Paragraph => {
                let text = self.inlines(node);
                self.push_block(prefix, &text);
            }

            NodeValue::Heading(heading) => {
                // Notion has three levels. Clamping is the honest lossy answer: the alternative is
                // a level-five heading importing as body text, which loses the structure entirely.
                let level = heading.level.clamp(1, self.dialect.max_heading()) as usize;
                let text = self.inlines(node);
                self.push_block(prefix, &format!("{} {}", "#".repeat(level), text.trim()));
            }

            NodeValue::ThematicBreak => self.push_block(prefix, "---"),

            NodeValue::CodeBlock(code) => {
                let language = code
                    .info
                    .split(|c: char| c == ',' || c.is_whitespace())
                    .next()
                    .unwrap_or("");
                let body = code.literal.trim_end_matches('\n');
                let fence = "`".repeat(
                    body.split(|c| c != '`')
                        .map(str::len)
                        .max()
                        .unwrap_or(0)
                        .max(2)
                        + 1,
                );
                self.push_block(prefix, &format!("{fence}{language}\n{body}\n{fence}"));
            }

            NodeValue::BlockQuote | NodeValue::MultilineBlockQuote(_) => {
                let mut inner = Markdown {
                    dialect: self.dialect,
                    ..Markdown::default()
                };
                inner.blocks(node, "");
                self.footnotes.append(&mut inner.footnotes);
                self.push_block(prefix, &prefix_lines(inner.out.trim_end(), "> "));
            }

            // A GitHub alert is a quote with a marker line. Notion has no such thing, so it becomes
            // a callout-shaped quote with the kind spelled out -- readable either way.
            NodeValue::Alert(alert) => {
                let kind = match alert.alert_type {
                    comrak::nodes::AlertType::Note => "NOTE",
                    comrak::nodes::AlertType::Tip => "TIP",
                    comrak::nodes::AlertType::Important => "IMPORTANT",
                    comrak::nodes::AlertType::Warning => "WARNING",
                    comrak::nodes::AlertType::Caution => "CAUTION",
                };

                let mut inner = Markdown {
                    dialect: self.dialect,
                    ..Markdown::default()
                };
                inner.blocks(node, "");
                self.footnotes.append(&mut inner.footnotes);

                let header = match self.dialect {
                    Dialect::GitHub => format!("[!{kind}]"),
                    Dialect::Notion => format!("**{kind}**"),
                };
                let body = format!("{header}\n{}", inner.out.trim_end());
                self.push_block(prefix, &prefix_lines(&body, "> "));
            }

            NodeValue::List(list) => {
                let mut number = list.start.max(1);
                let mut lines = Vec::new();

                for child in node.children() {
                    let marker = match list.list_type {
                        ListType::Ordered => {
                            let marker = match list.delimiter {
                                ListDelimType::Period => format!("{number}. "),
                                ListDelimType::Paren => format!("{number}) "),
                            };
                            number += 1;
                            marker
                        }
                        ListType::Bullet => "- ".to_owned(),
                    };

                    let marker = match &child.data.borrow().value {
                        NodeValue::TaskItem(task) if task.symbol.is_some() => format!("{marker}[x] "),
                        NodeValue::TaskItem(_) => format!("{marker}[ ] "),
                        _ => marker,
                    };

                    let mut inner = Markdown {
                        dialect: self.dialect,
                        tight: list.tight,
                        ..Markdown::default()
                    };
                    inner.blocks(child, "");
                    self.footnotes.append(&mut inner.footnotes);

                    let body = inner.out.trim_end();
                    if body.is_empty() {
                        continue;
                    }
                    let indent = " ".repeat(marker.chars().count());
                    lines.push(prefix_first(body, &marker, &indent));
                }

                self.push_block(prefix, &lines.join("\n"));
            }

            NodeValue::Item(_) | NodeValue::TaskItem(_) => self.blocks(node, prefix),

            NodeValue::Table(_) => self.table(node, prefix),

            // Neither target has definition lists. A bold term with its definition beneath it is
            // what somebody would have written instead, and it survives any importer.
            NodeValue::DescriptionList => {
                for child in node.children() {
                    self.block(child, prefix);
                }
            }

            NodeValue::DescriptionItem(_) => self.blocks(node, prefix),

            NodeValue::DescriptionTerm => {
                let text = self.inlines(node);
                if !text.trim().is_empty() {
                    self.push_block(prefix, &format!("**{}**", text.trim()));
                }
            }

            NodeValue::DescriptionDetails => self.blocks(node, prefix),

            NodeValue::FootnoteDefinition(definition) => {
                let mut inner = Markdown {
                    dialect: self.dialect,
                    ..Markdown::default()
                };
                inner.blocks(node, "");
                self.footnotes
                    .push((definition.name.clone(), inner.out.trim().replace('\n', " ")));
            }

            // Front matter is not content, and Notion imports it as a paragraph of YAML.
            NodeValue::FrontMatter(_) => {}

            NodeValue::HtmlBlock(html) => {
                if self.dialect.keeps_html() {
                    self.push_block(prefix, html.literal.trim_end());
                }
            }

            _ => self.blocks(node, prefix),
        }
    }

    fn table<'a>(&mut self, node: &'a AstNode<'a>, prefix: &str) {
        let alignments = match &node.data.borrow().value {
            NodeValue::Table(table) => table.alignments.clone(),
            _ => Vec::new(),
        };

        let mut rows: Vec<(bool, Vec<String>)> = Vec::new();
        for row in node.children() {
            let is_header = matches!(row.data.borrow().value, NodeValue::TableRow(true));
            let cells = row
                .children()
                .map(|cell| self.inlines(cell).replace('|', "\\|").trim().to_owned())
                .collect();
            rows.push((is_header, cells));
        }

        if rows.is_empty() {
            return;
        }

        let columns = rows.iter().map(|(_, cells)| cells.len()).max().unwrap_or(0);
        let mut lines = Vec::with_capacity(rows.len() + 1);

        for (index, (_, cells)) in rows.iter().enumerate() {
            let mut line = String::from("|");
            for column in 0..columns {
                line.push(' ');
                line.push_str(cells.get(column).map(String::as_str).unwrap_or(""));
                line.push_str(" |");
            }
            lines.push(line);

            if index == 0 {
                let mut divider = String::from("|");
                for column in 0..columns {
                    divider.push_str(match alignments.get(column) {
                        Some(TableAlignment::Left) => " :--- |",
                        Some(TableAlignment::Center) => " :---: |",
                        Some(TableAlignment::Right) => " ---: |",
                        _ => " --- |",
                    });
                }
                lines.push(divider);
            }
        }

        self.push_block(prefix, &lines.join("\n"));
    }

    fn inlines<'a>(&mut self, node: &'a AstNode<'a>) -> String {
        let mut out = String::new();
        for child in node.children() {
            out.push_str(&self.inline(child));
        }
        out
    }

    fn inline<'a>(&mut self, node: &'a AstNode<'a>) -> String {
        let value = node.data.borrow().value.clone();

        match value {
            NodeValue::Text(text) => text.to_string(),

            NodeValue::Code(code) => {
                let literal = &code.literal;
                let fence = "`".repeat(
                    literal.split(|c| c != '`').map(str::len).max().unwrap_or(0) + 1,
                );
                format!("{fence}{literal}{fence}")
            }

            NodeValue::Strong => format!("**{}**", self.inlines(node)),
            NodeValue::Emph => format!("*{}*", self.inlines(node)),
            NodeValue::Strikethrough => format!("~~{}~~", self.inlines(node)),

            // Neither target has these. Bold is the closest thing both of them do have, and it
            // keeps the emphasis the writer meant rather than the syntax they used.
            NodeValue::Highlight | NodeValue::Underline | NodeValue::Insert => {
                format!("**{}**", self.inlines(node))
            }

            // GitHub renders `<sub>` and `<sup>`; Notion does not, so the characters stay as text.
            NodeValue::Superscript => match self.dialect {
                Dialect::GitHub => format!("<sup>{}</sup>", self.inlines(node)),
                Dialect::Notion => format!("^{}", self.inlines(node)),
            },
            NodeValue::Subscript => match self.dialect {
                Dialect::GitHub => format!("<sub>{}</sub>", self.inlines(node)),
                Dialect::Notion => format!("_{}", self.inlines(node)),
            },

            NodeValue::SoftBreak => " ".to_owned(),
            NodeValue::LineBreak => "  \n".to_owned(),

            NodeValue::Link(link) => format!("[{}]({})", self.inlines(node), link.url),

            // A wiki link has no meaning outside the wiki it was written in, so it becomes the
            // ordinary link both targets understand.
            NodeValue::WikiLink(link) => {
                let label = self.inlines(node);
                let label = if label.trim().is_empty() {
                    link.url.clone()
                } else {
                    label
                };
                format!("[{label}]({})", link.url)
            }

            NodeValue::Image(link) => format!("![{}]({})", crate::parser::plain_text(node), link.url),

            // A footnote reference becomes a numbered marker with its text at the foot of the
            // document -- neither target has real footnotes, and dropping them loses the citation.
            NodeValue::FootnoteReference(reference) => format!("[{}]", reference.name),

            NodeValue::Math(math) => {
                if math.display_math {
                    format!("$${}$$", math.literal)
                } else {
                    format!("${}$", math.literal)
                }
            }

            NodeValue::HtmlInline(html) => {
                if self.dialect.keeps_html() {
                    html.clone()
                } else {
                    String::new()
                }
            }

            _ => self.inlines(node),
        }
    }
}

fn prefix_lines(text: &str, prefix: &str) -> String {
    text.split('\n')
        .map(|line| {
            if line.is_empty() {
                prefix.trim_end().to_owned()
            } else {
                format!("{prefix}{line}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn prefix_first(text: &str, first: &str, rest: &str) -> String {
    text.split('\n')
        .enumerate()
        .map(|(index, line)| {
            if index == 0 {
                format!("{first}{line}")
            } else if line.is_empty() {
                String::new()
            } else {
                format!("{rest}{line}")
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn confluence(source: &str) -> String {
        convert(source, Flavour::Gfm, Target::Confluence)
    }

    fn notion(source: &str) -> String {
        convert(source, Flavour::Gfm, Target::Notion)
    }

    fn github(source: &str) -> String {
        convert(source, Flavour::Pandoc, Target::GitHubReadme)
    }

    // ---------------------------------------------------------------- Confluence

    #[test]
    fn confluence_writes_headings_and_paragraphs() {
        let out = confluence("# Title\n\nSome text.\n");
        assert!(out.contains("<h1>Title</h1>"));
        assert!(out.contains("<p>Some text.</p>"));
    }

    #[test]
    fn a_code_block_becomes_a_confluence_macro_rather_than_a_pre() {
        // The difference between a page Confluence can edit and one it can only display.
        let out = confluence("```rust\nfn main() {}\n```\n");
        assert!(out.contains("ac:name=\"code\""), "{out}");
        assert!(out.contains("<ac:parameter ac:name=\"language\">rust</ac:parameter>"));
        assert!(out.contains("<![CDATA[fn main() {}"), "{out}");
    }

    #[test]
    fn code_containing_a_cdata_terminator_does_not_break_the_document() {
        let out = confluence("```\nif (a[b[c]]>d) {}\n```\n");
        assert!(out.contains("]]]]><![CDATA[>"), "{out}");
    }

    #[test]
    fn a_task_list_becomes_a_confluence_task_list() {
        let out = confluence("- [x] done\n- [ ] todo\n");
        assert!(out.contains("<ac:task-list>"));
        assert!(out.contains("<ac:task-status>complete</ac:task-status>"));
        assert!(out.contains("<ac:task-status>incomplete</ac:task-status>"));
    }

    #[test]
    fn an_alert_becomes_the_matching_confluence_macro() {
        let out = confluence("> [!WARNING]\n> Mind the gap.\n");
        assert!(out.contains("ac:name=\"warning\""), "{out}");
        assert!(out.contains("Mind the gap."));
    }

    #[test]
    fn confluence_escapes_xml_rather_than_producing_a_broken_page() {
        // Storage format is XML: a bare ampersand is a parse error, not a rendering quirk.
        let out = confluence("Tom & Jerry <b>\n");
        assert!(out.contains("Tom &amp; Jerry"), "{out}");
        assert!(!out.contains("Tom & Jerry"));
    }

    #[test]
    fn an_image_is_an_attachment_when_it_is_local_and_a_url_when_it_is_not() {
        assert!(confluence("![](assets/a.png)\n").contains("ri:attachment ri:filename=\"a.png\""));
        assert!(confluence("![](https://x/a.png)\n").contains("ri:url ri:value=\"https://x/a.png\""));
    }

    #[test]
    fn confluence_keeps_table_headers_as_headers() {
        let out = confluence("| a | b |\n|---|---|\n| 1 | 2 |\n");
        assert!(out.contains("<th>a</th>"), "{out}");
        assert!(out.contains("<td>1</td>"));
    }

    // ---------------------------------------------------------------- Notion

    #[test]
    fn notion_clamps_headings_to_the_three_levels_it_has() {
        let out = notion("# One\n\n#### Four\n\n###### Six\n");
        assert!(out.contains("# One"));
        assert!(out.contains("### Four"), "{out}");
        assert!(out.contains("### Six"));
        assert!(!out.contains("#### "));
    }

    #[test]
    fn notion_keeps_lists_tables_and_task_lists() {
        let out = notion("- one\n- [x] done\n\n| a |\n|---|\n| 1 |\n");
        assert!(out.contains("- one"));
        assert!(out.contains("- [x] done"));
        assert!(out.contains("| a |"));
    }

    #[test]
    fn notion_drops_raw_html_rather_than_importing_it_as_text() {
        // Notion's importer shows unknown HTML as literal characters in the middle of the page.
        let out = notion("Before\n\n<div class=\"x\">markup</div>\n\nAfter\n");
        assert!(!out.contains("<div"), "{out}");
        assert!(out.contains("Before") && out.contains("After"));
    }

    #[test]
    fn a_footnote_becomes_a_marker_and_a_note_at_the_end() {
        let out = notion("Claim[^1].\n\n[^1]: The evidence.\n");
        assert!(out.contains("Claim[1]"), "{out}");
        assert!(out.contains("The evidence."), "{out}");
    }

    #[test]
    fn front_matter_is_not_carried_into_the_page() {
        let out = notion("---\ntitle: Draft\n---\n\n# Heading\n");
        assert!(!out.contains("title: Draft"), "{out}");
        assert!(out.contains("# Heading"));
    }

    // ---------------------------------------------------------------- GitHub

    #[test]
    fn pandoc_syntax_is_translated_into_what_github_renders() {
        // On GitHub these are the characters you typed unless they are translated.
        let out = github("H~2~O and x^2^ and ==marked==\n");
        assert!(out.contains("<sub>2</sub>"), "{out}");
        assert!(out.contains("<sup>2</sup>"), "{out}");
        assert!(out.contains("**marked**"), "{out}");
    }

    #[test]
    fn a_definition_list_becomes_a_bold_term_and_its_definition() {
        let out = github("Term\n\n: The definition.\n");
        assert!(out.contains("**Term**"), "{out}");
        assert!(out.contains("The definition."), "{out}");
    }

    #[test]
    fn github_keeps_the_gfm_it_already_had() {
        let out = convert(
            "# Title\n\n- [x] done\n\n| a | b |\n|:--|--:|\n| 1 | 2 |\n\n> [!NOTE]\n> Careful.\n",
            Flavour::Gfm,
            Target::GitHubReadme,
        );
        assert!(out.contains("# Title"));
        assert!(out.contains("- [x] done"));
        assert!(out.contains("| :--- |"), "{out}");
        assert!(out.contains("> [!NOTE]"), "{out}");
    }

    #[test]
    fn a_wiki_link_becomes_an_ordinary_link() {
        let out = convert("See [[Guide|the guide]].\n", Flavour::MyST, Target::GitHubReadme);
        assert!(out.contains("[the guide](Guide)"), "{out}");
    }

    #[test]
    fn code_fences_survive_with_their_language_and_grow_when_they_have_to() {
        let out = github("```python\nprint('hi')\n```\n");
        assert!(out.contains("```python\nprint('hi')\n```"), "{out}");

        let nested = github("````\n```\ninner\n```\n````\n");
        assert!(nested.contains("````"), "{nested}");
    }

    #[test]
    fn nested_lists_keep_their_nesting() {
        let out = github("- outer\n  - inner\n- next\n");
        assert!(out.contains("- outer\n  - inner"), "{out}");
    }

    #[test]
    fn a_quote_stays_a_quote_and_keeps_its_paragraphs() {
        let out = github("> one\n>\n> two\n");
        assert!(out.contains("> one"), "{out}");
        assert!(out.contains("> two"), "{out}");
    }

    #[test]
    fn every_target_survives_an_empty_document() {
        for target in [Target::Confluence, Target::Notion, Target::GitHubReadme] {
            let out = convert("", Flavour::Gfm, target);
            assert!(out.trim().is_empty(), "{target:?} produced {out:?}");
        }
    }

    #[test]
    fn korean_and_emoji_survive_every_target() {
        for target in [Target::Confluence, Target::Notion, Target::GitHubReadme] {
            let out = convert("# 한국어 제목 🪶\n", Flavour::Gfm, target);
            assert!(out.contains("한국어 제목 🪶"), "{target:?} lost the text: {out}");
        }
    }

    #[test]
    fn targets_round_trip_through_their_wire_values() {
        for target in [Target::Confluence, Target::Notion, Target::GitHubReadme] {
            assert_eq!(Target::from_u8(target as u8), Some(target));
        }
        assert_eq!(Target::from_u8(9), None);
    }
}
