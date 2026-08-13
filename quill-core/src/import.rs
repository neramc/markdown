//! HTML to Markdown: what makes a paste from anywhere land as clean source.
//!
//! When something is copied out of a browser, Word, Notion, Google Docs or a word processor's own
//! viewer, the clipboard carries an HTML flavour alongside the plain text. That HTML is where the
//! structure lives — the headings, the lists, the table, the links — and the plain-text flavour has
//! already thrown all of it away. Pasting the plain text loses the document; pasting the HTML into
//! a Markdown file leaves a wall of `<span style=…>`. Converting is the only option that keeps what
//! the writer copied and produces something they would have typed.
//!
//! Three things make real-world clipboard HTML harder than the tidy fragments a Markdown renderer
//! emits, and each is handled explicitly below:
//!
//! * **Emphasis is often a style, not a tag.** Google Docs writes bold as
//!   `<span style="font-weight:700">`, and every `<b>`/`<i>` it emits is decoration around that. So
//!   the converter reads `font-weight`, `font-style` and `text-decoration` as well as the tags —
//!   including Docs' notorious document-wide `<b style="font-weight:normal">` wrapper, which means
//!   the opposite of what the tag says.
//! * **Word's lists are not lists.** Desktop Word writes each item as a paragraph carrying
//!   `mso-list:` in its style, with the bullet glyph in a span marked `mso-list:Ignore`. Taken at
//!   face value that is a run of paragraphs beginning with `·`. Read properly it is a nested list,
//!   and the marker says whether it is numbered.
//! * **Whitespace means nothing until it does.** HTML collapses runs of space; Markdown does not,
//!   and two trailing spaces are a line break. Text is collapsed on the way through, and the only
//!   significant whitespace that survives is inside `<pre>`.
//!
//! The output targets GFM, because that is what a `.md` file almost always is. Where the source
//! uses something GFM has no syntax for — `<sup>`, `<mark>`, `<details>` — the HTML is passed
//! through rather than dropped, which is what GitHub itself renders.

use crate::html::{Element, Node, parse};

/// Converts an HTML fragment to Markdown.
///
/// Never fails: the parser it is built on is tolerant by design, and anything this converter does
/// not recognise degrades to its text content rather than to an error. A paste that produces
/// slightly wrong Markdown is a paste the writer can fix; one that produces an error message is a
/// paste that lost their work.
pub fn html_to_markdown(html: &str) -> String {
    let nodes = parse(html);
    let mut context = Context::default();
    let blocks = convert_blocks(&nodes, &mut context);
    let mut markdown = blocks.join("\n\n");
    if !markdown.is_empty() {
        markdown.push('\n');
    }
    markdown
}

/// State carried down the tree.
#[derive(Default)]
struct Context {
    /// Google Docs wraps its whole payload in `<b style="font-weight:normal">`. Once that has been
    /// seen, a bare `<b>` inside it is decoration rather than emphasis.
    bold_is_meaningless: bool,
    /// Word's list paragraphs, accumulated so a run of them becomes one list.
    word_list: Vec<WordListItem>,
}

/// One Word paragraph that is really a list item.
struct WordListItem {
    /// Zero-based nesting depth, from Word's own `level<N>`.
    depth: usize,
    ordered: bool,
    content: String,
}

// ---------------------------------------------------------------------------------------- blocks

/// Elements that carry no content worth importing.
///
/// `o:p` and `xml` are Word's; the rest are the head of a full document, which is what lands on the
/// clipboard when a whole page is copied.
const DISCARDED: &[&str] = &[
    "script", "style", "head", "meta", "link", "title", "noscript", "o:p", "xml", "svg", "iframe",
    "object", "embed", "form", "button", "input", "select", "textarea",
];

/// Elements that group other blocks without meaning anything themselves.
const TRANSPARENT: &[&str] = &[
    "html", "body", "div", "section", "article", "main", "header", "footer", "aside", "nav",
    "figure", "center", "font", "template", "picture",
];

/// Converts a run of nodes into block-level Markdown, one entry per block.
fn convert_blocks(nodes: &[Node], context: &mut Context) -> Vec<String> {
    let mut blocks = Vec::new();
    // Inline content between blocks is a paragraph in everything but name; Word's own list
    // paragraphs are gathered up so a run of them becomes one list rather than several.
    let mut inline = String::new();

    for node in nodes {
        match node {
            Node::Text(text) => inline.push_str(&escape_text(&collapse(text))),
            Node::Element(element) if DISCARDED.contains(&element.tag.as_str()) => {}
            Node::Element(element) if is_block(&element.tag) => {
                flush_inline(&mut inline, &mut blocks);
                convert_block_element(element, context, &mut blocks);
            }
            Node::Element(element) => {
                inline.push_str(&convert_inline_element(element, context));
            }
        }
    }

    flush_inline(&mut inline, &mut blocks);
    flush_word_list(context, &mut blocks);
    blocks
}

fn flush_inline(inline: &mut String, blocks: &mut Vec<String>) {
    let text = inline.trim().to_owned();
    inline.clear();
    if !text.is_empty() {
        blocks.push(text);
    }
}

fn is_block(tag: &str) -> bool {
    matches!(
        tag,
        "p" | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "ul"
            | "ol"
            | "li"
            | "blockquote"
            | "pre"
            | "table"
            | "hr"
            | "dl"
            | "dt"
            | "dd"
            | "details"
            | "figcaption"
    ) || TRANSPARENT.contains(&tag)
}

fn convert_block_element(element: &Element, context: &mut Context, blocks: &mut Vec<String>) {
    // A Word list paragraph is not a paragraph. It has to be recognised before the generic `p`
    // handling, and a run of them collapses into one list at the end.
    if element.tag == "p"
        && let Some(item) = word_list_item(element, context)
    {
        context.word_list.push(item);
        return;
    }
    flush_word_list(context, blocks);

    match element.tag.as_str() {
        tag if TRANSPARENT.contains(&tag) => {
            blocks.extend(convert_blocks(&element.children, context));
        }

        "p" | "figcaption" | "dd" => {
            let text = convert_inline(&element.children, context);
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                blocks.push(trimmed.to_owned());
            }
        }

        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            let level = element.tag[1..].parse::<usize>().unwrap_or(1);
            let text = convert_inline(&element.children, context);
            // A heading is one line by definition; a `<br>` inside one becomes a space rather than
            // splitting the document.
            let text = text.replace('\n', " ");
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                blocks.push(format!("{} {trimmed}", "#".repeat(level)));
            }
        }

        // A definition term has no GFM syntax. Bold is what a reader would have typed for it, and
        // it survives a round trip through any dialect.
        "dt" => {
            let text = convert_inline(&element.children, context);
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                blocks.push(emphasise(trimmed, "**"));
            }
        }

        "dl" => blocks.extend(convert_blocks(&element.children, context)),

        "ul" | "ol" => {
            if let Some(list) = convert_list(element, context) {
                blocks.push(list);
            }
        }

        // A stray `<li>` outside a list: keep the content rather than the structure.
        "li" => blocks.extend(convert_blocks(&element.children, context)),

        "blockquote" => {
            let inner = convert_blocks(&element.children, context);
            if !inner.is_empty() {
                blocks.push(prefix_lines(&inner.join("\n\n"), "> ", "> "));
            }
        }

        "pre" => blocks.push(convert_pre(element)),

        "table" => {
            if let Some(table) = convert_table(element, context) {
                blocks.push(table);
            }
        }

        "hr" => blocks.push("---".to_owned()),

        // GFM renders `<details>` and nothing else does it, so it goes through as written.
        "details" => blocks.push(render_html(element)),

        _ => blocks.extend(convert_blocks(&element.children, context)),
    }
}

/// Renders a fenced code block from a `<pre>`.
fn convert_pre(element: &Element) -> String {
    // `<pre><code class="language-rust">` is how every syntax highlighter marks the language, and
    // the class survives a copy out of a rendered page.
    let (language, source) = match element.children.iter().find_map(|node| match node {
        Node::Element(child) if child.tag == "code" => Some(child),
        _ => None,
    }) {
        Some(code) => (
            language_of(code),
            text_content(&Node::Element(code.clone())),
        ),
        None => (
            language_of(element),
            text_content(&Node::Element(element.clone())),
        ),
    };

    let source = source.trim_end_matches('\n');
    // A fence has to be longer than the longest run of backticks it contains, or the block ends
    // early and the rest of the paste lands as prose.
    let longest_run = source
        .split(|c| c != '`')
        .map(str::len)
        .max()
        .unwrap_or(0)
        .max(2);
    let fence = "`".repeat(longest_run + 1);

    format!("{fence}{language}\n{source}\n{fence}")
}

/// The language a highlighter recorded on an element, as a fence info string.
fn language_of(element: &Element) -> String {
    let classes = element.attribute("class").unwrap_or_default();
    for class in classes.split_whitespace() {
        for prefix in ["language-", "lang-", "highlight-source-", "brush:"] {
            if let Some(language) = class.strip_prefix(prefix)
                && !language.is_empty()
            {
                return language.to_ascii_lowercase();
            }
        }
    }
    element
        .attribute("data-language")
        .map(str::to_ascii_lowercase)
        .unwrap_or_default()
}

/// Renders a `<ul>` or `<ol>` and everything nested inside it.
fn convert_list(element: &Element, context: &mut Context) -> Option<String> {
    let ordered = element.tag == "ol";
    let start: usize = element
        .attribute("start")
        .and_then(|value| value.trim().parse().ok())
        .unwrap_or(1);

    let mut lines: Vec<String> = Vec::new();
    let mut number = start;

    for child in &element.children {
        let Node::Element(item) = child else { continue };
        if item.tag != "li" {
            continue;
        }

        let marker = if ordered {
            let marker = format!("{number}. ");
            number += 1;
            marker
        } else {
            "- ".to_owned()
        };

        // A task list survives the round trip: GitHub and Notion both emit a disabled checkbox, and
        // dropping it would turn a checklist into a bullet list.
        let marker = match checkbox_state(item) {
            Some(true) => format!("{marker}[x] "),
            Some(false) => format!("{marker}[ ] "),
            None => marker,
        };

        let inner = join_item_blocks(&convert_blocks(&item.children, context));
        if inner.trim().is_empty() {
            continue;
        }
        // Continuation lines line up under the text, not under the marker, which is what makes a
        // nested list nest instead of terminating its parent.
        let indent = " ".repeat(marker.chars().count());
        lines.push(prefix_lines(inner.trim(), &marker, &indent));
    }

    if lines.is_empty() {
        return None;
    }
    Some(lines.join("\n"))
}

/// Joins one list item's blocks, keeping a nested list tight against the text it hangs from.
///
/// `<li>outer<ul><li>inner</li></ul></li>` is a tight nested list, and separating the two with a
/// blank line makes it a loose one — which Markdown renders with a paragraph's worth of space
/// between every item. The source said nothing about spacing, so the tighter reading is the one
/// that matches what was copied.
fn join_item_blocks(blocks: &[String]) -> String {
    let mut out = String::new();
    for (index, block) in blocks.iter().enumerate() {
        if index > 0 {
            out.push_str(if starts_a_list(block) { "\n" } else { "\n\n" });
        }
        out.push_str(block);
    }
    out
}

fn starts_a_list(block: &str) -> bool {
    let line = block.lines().next().unwrap_or_default();
    let rest = line.trim_start();
    rest.starts_with("- ")
        || rest.starts_with("* ")
        || rest.starts_with("+ ")
        || rest
            .split_once(". ")
            .is_some_and(|(head, _)| !head.is_empty() && head.chars().all(|c| c.is_ascii_digit()))
}

/// Whether a list item carries a checkbox, and whether it is ticked.
fn checkbox_state(item: &Element) -> Option<bool> {
    fn find(nodes: &[Node]) -> Option<bool> {
        for node in nodes {
            let Node::Element(element) = node else {
                continue;
            };
            if element.tag == "input"
                && element
                    .attribute("type")
                    .is_some_and(|kind| kind.eq_ignore_ascii_case("checkbox"))
            {
                return Some(element.attribute("checked").is_some());
            }
            if let Some(found) = find(&element.children) {
                return Some(found);
            }
        }
        None
    }
    find(&item.children)
}

/// Renders a GFM table, or `None` when there are no rows to render.
fn convert_table(element: &Element, context: &mut Context) -> Option<String> {
    let mut rows: Vec<Vec<String>> = Vec::new();
    let mut header_rows = 0usize;

    collect_rows(
        &element.children,
        context,
        &mut rows,
        &mut header_rows,
        false,
    );

    let rows: Vec<Vec<String>> = rows.into_iter().filter(|row| !row.is_empty()).collect();
    if rows.is_empty() {
        return None;
    }

    let columns = rows.iter().map(Vec::len).max().unwrap_or(0);
    if columns == 0 {
        return None;
    }

    let mut lines = Vec::with_capacity(rows.len() + 1);
    let mut body_start = 0usize;

    if header_rows > 0 {
        lines.push(render_row(&rows[0], columns));
        body_start = 1;
    } else {
        // GFM has no headerless table. An empty header row is the honest rendering: it says the
        // source had no header rather than promoting the first row of data into one.
        lines.push(render_row(&vec![String::new(); columns], columns));
    }
    lines.push(format!("|{}", " --- |".repeat(columns)));

    for row in &rows[body_start..] {
        lines.push(render_row(row, columns));
    }

    Some(lines.join("\n"))
}

/// Walks a table's rows, wherever `thead`/`tbody`/`tfoot` put them.
fn collect_rows(
    nodes: &[Node],
    context: &mut Context,
    rows: &mut Vec<Vec<String>>,
    header_rows: &mut usize,
    in_head: bool,
) {
    for node in nodes {
        let Node::Element(element) = node else {
            continue;
        };
        match element.tag.as_str() {
            "thead" => collect_rows(&element.children, context, rows, header_rows, true),
            "tbody" | "tfoot" | "colgroup" => {
                collect_rows(&element.children, context, rows, header_rows, false);
            }
            "tr" => {
                let cells: Vec<String> = element
                    .children
                    .iter()
                    .filter_map(|child| match child {
                        Node::Element(cell) if cell.tag == "td" || cell.tag == "th" => {
                            Some(cell_text(cell, context))
                        }
                        _ => None,
                    })
                    .collect();

                let all_headers = !cells.is_empty()
                    && element.children.iter().all(|child| match child {
                        Node::Element(cell) => cell.tag != "td",
                        Node::Text(text) => text.trim().is_empty(),
                    });

                if (in_head || all_headers) && rows.is_empty() {
                    *header_rows = 1;
                }
                rows.push(cells);
            }
            _ => collect_rows(&element.children, context, rows, header_rows, in_head),
        }
    }
}

/// One cell's content, flattened to the single line a GFM table allows.
fn cell_text(cell: &Element, context: &mut Context) -> String {
    let text = convert_inline(&cell.children, context);
    text.replace("\\\n", "<br>")
        .replace('\n', " ")
        .replace('|', "\\|")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn render_row(cells: &[String], columns: usize) -> String {
    let mut line = String::from("|");
    for index in 0..columns {
        line.push(' ');
        line.push_str(cells.get(index).map(String::as_str).unwrap_or(""));
        line.push_str(" |");
    }
    line
}

// ------------------------------------------------------------------------------------ Word lists

/// Recognises a Word list paragraph and converts it into a list item.
fn word_list_item(element: &Element, context: &mut Context) -> Option<WordListItem> {
    let style = element.attribute("style").unwrap_or_default();
    let lowercase = style.to_ascii_lowercase();
    if !lowercase.contains("mso-list:") {
        return None;
    }

    // `mso-list:l0 level2 lfo1` — the level is the nesting depth, one-based.
    let depth = lowercase
        .split("level")
        .nth(1)
        .and_then(|rest| {
            rest.chars()
                .take_while(char::is_ascii_digit)
                .collect::<String>()
                .parse::<usize>()
                .ok()
        })
        .unwrap_or(1)
        .saturating_sub(1);

    // The marker Word drew lives in a span it tells renderers to ignore. It is the only place the
    // list's kind is recorded, so it is read before being dropped.
    let marker = marker_text(&element.children).unwrap_or_default();
    let ordered = marker
        .trim_start()
        .chars()
        .next()
        .is_some_and(|c| c.is_ascii_digit());

    let content = convert_inline(&element.children, context);
    let content = content.trim().to_owned();
    if content.is_empty() {
        return None;
    }

    Some(WordListItem {
        depth,
        ordered,
        content,
    })
}

/// The text of the `mso-list:Ignore` span, which holds the bullet or number Word drew.
fn marker_text(nodes: &[Node]) -> Option<String> {
    for node in nodes {
        let Node::Element(element) = node else {
            continue;
        };
        if is_ignored_marker(element) {
            return Some(text_content(node));
        }
        if let Some(found) = marker_text(&element.children) {
            return Some(found);
        }
    }
    None
}

fn is_ignored_marker(element: &Element) -> bool {
    element
        .attribute("style")
        .is_some_and(|style| style.to_ascii_lowercase().contains("mso-list:ignore"))
}

/// Turns the gathered Word paragraphs into one nested list.
fn flush_word_list(context: &mut Context, blocks: &mut Vec<String>) {
    if context.word_list.is_empty() {
        return;
    }
    let items = std::mem::take(&mut context.word_list);

    // Numbering restarts at each depth, the way it does in the document the paragraphs came from.
    let mut counters: Vec<usize> = Vec::new();
    let mut lines = Vec::with_capacity(items.len());

    for item in items {
        counters.truncate(item.depth + 1);
        while counters.len() <= item.depth {
            counters.push(0);
        }
        counters[item.depth] += 1;

        let marker = if item.ordered {
            format!("{}. ", counters[item.depth])
        } else {
            "- ".to_owned()
        };
        let indent = "  ".repeat(item.depth);
        lines.push(format!("{indent}{marker}{}", item.content));
    }

    blocks.push(lines.join("\n"));
}

// ---------------------------------------------------------------------------------------- inline

fn convert_inline(nodes: &[Node], context: &mut Context) -> String {
    let mut out = String::new();
    for node in nodes {
        match node {
            Node::Text(text) => out.push_str(&escape_text(&collapse(text))),
            Node::Element(element) if DISCARDED.contains(&element.tag.as_str()) => {}
            Node::Element(element) if is_ignored_marker(element) => {}
            Node::Element(element) => out.push_str(&convert_inline_element(element, context)),
        }
    }
    out
}

fn convert_inline_element(element: &Element, context: &mut Context) -> String {
    match element.tag.as_str() {
        "br" => "\\\n".to_owned(),

        "a" => {
            let inner = convert_inline(&element.children, context).trim().to_owned();
            let href = element.attribute("href").unwrap_or_default().trim();
            // A named anchor or a `javascript:` handler is not a destination anybody wants pasted
            // into a document; its text still is.
            if href.is_empty()
                || href.starts_with('#')
                || href.to_ascii_lowercase().starts_with("javascript:")
            {
                return inner;
            }
            if inner.is_empty() {
                return format!("<{href}>");
            }
            match element.attribute("title").filter(|title| !title.is_empty()) {
                Some(title) => format!(
                    "[{inner}]({} \"{}\")",
                    encode_url(href),
                    escape_quotes(title)
                ),
                None => format!("[{inner}]({})", encode_url(href)),
            }
        }

        "img" => {
            let source = element.attribute("src").unwrap_or_default().trim();
            if source.is_empty() {
                return String::new();
            }
            let alt = element.attribute("alt").unwrap_or_default();
            match element.attribute("title").filter(|title| !title.is_empty()) {
                Some(title) => format!(
                    "![{}]({} \"{}\")",
                    escape_text(alt),
                    encode_url(source),
                    escape_quotes(title)
                ),
                None => format!("![{}]({})", escape_text(alt), encode_url(source)),
            }
        }

        "code" | "kbd" | "samp" | "tt" => {
            let literal = text_content(&Node::Element(element.clone()));
            let literal = collapse(&literal);
            if literal.trim().is_empty() {
                return String::new();
            }
            code_span(literal.trim())
        }

        "strong" | "b" => {
            // Google Docs wraps its entire payload in `<b style="font-weight:normal">`. Taking that
            // at face value makes the whole paste bold, which is the single most visible way this
            // conversion can go wrong.
            let neutralised = style_weight(element) == Some(Weight::Normal);
            let previously = context.bold_is_meaningless;
            if neutralised {
                context.bold_is_meaningless = true;
            }
            let inner = convert_inline(&element.children, context);
            context.bold_is_meaningless = previously;

            if neutralised || context.bold_is_meaningless {
                inner
            } else {
                emphasise(&inner, "**")
            }
        }

        "em" | "i" | "cite" | "var" | "dfn" => {
            emphasise(&convert_inline(&element.children, context), "*")
        }

        "del" | "s" | "strike" => emphasise(&convert_inline(&element.children, context), "~~"),

        // GFM has no syntax for these; GitHub renders the tags, so they go through as written.
        "sup" | "sub" | "mark" | "u" | "abbr" | "ins" | "small" | "q" | "time" | "ruby" | "rt" => {
            let inner = convert_inline(&element.children, context);
            if inner.trim().is_empty() {
                String::new()
            } else {
                format!("<{tag}>{inner}</{tag}>", tag = element.tag)
            }
        }

        // A `<span>` is where a word processor hides its formatting. The tag means nothing; the
        // style attribute means everything.
        "span" | "font" | "label" | "bdi" | "bdo" => {
            let inner = convert_inline(&element.children, context);
            apply_styles(element, inner, context)
        }

        // Anything unrecognised still has content, and the content is the point.
        _ => {
            let inner = convert_inline(&element.children, context);
            apply_styles(element, inner, context)
        }
    }
}

/// Wraps `inner` in `marker`, moving any surrounding whitespace outside the markers.
///
/// `** text **` is not emphasis in any Markdown dialect: the opening marker must be followed by a
/// non-space and the closing one preceded by one. Word processors produce exactly that shape all
/// the time, because a styled run routinely includes the space that separates it from the next
/// word.
fn emphasise(inner: &str, marker: &str) -> String {
    let trimmed = inner.trim();
    if trimmed.is_empty() {
        return inner.to_owned();
    }
    let leading = &inner[..inner.len() - inner.trim_start().len()];
    let trailing = &inner[inner.trim_end().len()..];
    format!("{leading}{marker}{trimmed}{marker}{trailing}")
}

/// Emphasis the source expressed as CSS rather than as a tag.
fn apply_styles(element: &Element, inner: String, _context: &mut Context) -> String {
    let Some(style) = element.attribute("style") else {
        return inner;
    };
    if inner.trim().is_empty() {
        return inner;
    }
    let style = style.to_ascii_lowercase();

    let mut result = inner;
    if declaration(&style, "font-style").is_some_and(|value| value.starts_with("italic")) {
        result = emphasise(&result, "*");
    }
    if declaration(&style, "text-decoration").is_some_and(|value| value.contains("line-through")) {
        result = emphasise(&result, "~~");
    }
    if style_weight(element) == Some(Weight::Bold) {
        result = emphasise(&result, "**");
    }
    result
}

#[derive(PartialEq, Eq)]
enum Weight {
    Bold,
    Normal,
}

/// Reads `font-weight`, which is how Google Docs and Word both record bold.
fn style_weight(element: &Element) -> Option<Weight> {
    let style = element.attribute("style")?.to_ascii_lowercase();
    let value = declaration(&style, "font-weight")?;
    if value.starts_with("bold") {
        return Some(Weight::Bold);
    }
    if value.starts_with("normal") || value.starts_with("light") {
        return Some(Weight::Normal);
    }
    match value.trim().parse::<u32>() {
        Ok(weight) if weight >= 600 => Some(Weight::Bold),
        Ok(_) => Some(Weight::Normal),
        Err(_) => None,
    }
}

/// One declaration's value out of an inline style attribute.
fn declaration<'a>(style: &'a str, property: &str) -> Option<&'a str> {
    style.split(';').find_map(|declaration| {
        let (name, value) = declaration.split_once(':')?;
        // Word writes `mso-style-textfill-fill-color`, which contains `color` but is not it.
        (name.trim() == property).then(|| value.trim())
    })
}

/// Wraps a literal in the shortest backtick fence that can hold it.
fn code_span(literal: &str) -> String {
    let longest_run = literal.split(|c| c != '`').map(str::len).max().unwrap_or(0);
    let fence = "`".repeat(longest_run + 1);
    // A literal that starts or ends with a backtick needs a space inside the fence, or the fence
    // and the content run together into a longer fence.
    if literal.starts_with('`') || literal.ends_with('`') {
        format!("{fence} {literal} {fence}")
    } else {
        format!("{fence}{literal}{fence}")
    }
}

// ----------------------------------------------------------------------------------------- text

/// Collapses HTML whitespace: every run of spaces, tabs and newlines becomes one space.
///
/// Non-breaking spaces become ordinary ones. They are what a word processor emits for indentation
/// and for the gap after a bullet, and leaving them in produces a document full of characters that
/// look like spaces, do not wrap, and cannot be found with the space bar.
fn collapse(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut in_space = false;
    for character in text.chars() {
        let is_space =
            character.is_whitespace() || character == '\u{a0}' || character == '\u{200b}';
        if is_space {
            if !in_space {
                out.push(' ');
            }
            in_space = true;
        } else {
            out.push(character);
            in_space = false;
        }
    }
    out
}

/// Escapes the characters that would otherwise be read as Markdown.
///
/// Deliberately narrow. Escaping everything that *could* be syntax produces `\.` after every list
/// number and `\-` in every hyphenated word, which is technically correct and looks like a bug. The
/// rule here is to escape a character only where it would actually change the parse.
fn escape_text(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let characters: Vec<char> = text.chars().collect();

    for (index, &character) in characters.iter().enumerate() {
        let at_start = out.is_empty() || out.ends_with('\n');
        let previous = index.checked_sub(1).map(|i| characters[i]);
        let next = characters.get(index + 1).copied();

        let escape = match character {
            '\\' | '`' | '*' | '[' | ']' => true,
            // Underscores are only emphasis at a word boundary, which is what lets `snake_case`
            // through unescaped — the form they overwhelmingly appear in.
            '_' => {
                !previous.is_some_and(char::is_alphanumeric)
                    || !next.is_some_and(char::is_alphanumeric)
            }
            // A `<` only matters where it could open a tag or an autolink.
            '<' => next.is_some_and(|c| c.is_ascii_alphabetic() || c == '/' || c == '!'),
            // An `&` only matters where it could open an entity.
            '&' => next.is_some_and(|c| c.is_ascii_alphanumeric() || c == '#'),
            // These are only syntax at the start of a line.
            '#' | '>' | '|' => at_start,
            '-' | '+' => at_start && next.is_some_and(char::is_whitespace),
            '.' | ')' => {
                at_start
                    && previous.is_some_and(|c| c.is_ascii_digit())
                    && next.is_some_and(char::is_whitespace)
            }
            _ => false,
        };

        if escape {
            out.push('\\');
        }
        out.push(character);
    }
    out
}

fn escape_quotes(text: &str) -> String {
    text.replace('"', "\\\"")
}

/// Percent-encodes the characters that would end a Markdown destination early.
fn encode_url(url: &str) -> String {
    let needs_wrapping = url.contains(' ') || url.contains('(') || url.contains(')');
    let encoded = url.replace(' ', "%20");
    if needs_wrapping {
        format!("<{}>", encoded.replace('<', "%3C").replace('>', "%3E"))
    } else {
        encoded
    }
}

/// A node's text, with nothing escaped and nothing collapsed.
fn text_content(node: &Node) -> String {
    match node {
        Node::Text(text) => text.clone(),
        Node::Element(element) => element.children.iter().map(text_content).collect(),
    }
}

/// Renders an element back to HTML, for the tags GFM keeps rather than converts.
fn render_html(element: &Element) -> String {
    let mut out = format!("<{}", element.tag);
    for (name, value) in &element.attributes {
        out.push_str(&format!(" {name}=\"{}\"", value.replace('"', "&quot;")));
    }
    out.push('>');
    for child in &element.children {
        match child {
            Node::Text(text) => out.push_str(text),
            Node::Element(child) => out.push_str(&render_html(child)),
        }
    }
    out.push_str(&format!("</{}>", element.tag));
    out
}

/// Prefixes the first line with `first` and every later one with `rest`.
fn prefix_lines(text: &str, first: &str, rest: &str) -> String {
    let mut out = String::new();
    for (index, line) in text.lines().enumerate() {
        if index > 0 {
            out.push('\n');
        }
        if index == 0 {
            out.push_str(first);
        } else if !line.is_empty() {
            out.push_str(rest);
        } else {
            out.push_str(rest.trim_end());
        }
        out.push_str(line);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn convert(html: &str) -> String {
        html_to_markdown(html)
    }

    #[test]
    fn converts_the_ordinary_shapes() {
        assert_eq!(convert("<h2>Title</h2>"), "## Title\n");
        assert_eq!(convert("<p>Hello there.</p>"), "Hello there.\n");
        assert_eq!(convert("<p><strong>bold</strong></p>"), "**bold**\n");
        assert_eq!(convert("<p><em>italic</em></p>"), "*italic*\n");
        assert_eq!(convert("<p><del>gone</del></p>"), "~~gone~~\n");
        assert_eq!(convert("<hr>"), "---\n");
    }

    #[test]
    fn a_paste_from_a_web_page_keeps_its_structure() {
        let html = r#"
            <article>
              <h1>Guide</h1>
              <p>Read the <a href="https://example.com/docs" title="Docs">documentation</a>.</p>
              <ul><li>first</li><li>second</li></ul>
            </article>
        "#;
        assert_eq!(
            convert(html),
            "# Guide\n\nRead the [documentation](https://example.com/docs \"Docs\").\n\n- first\n- second\n",
        );
    }

    #[test]
    fn google_docs_bold_is_a_style_and_its_wrapper_is_a_lie() {
        // Docs wraps the whole payload in a `<b>` that declares itself not bold, and expresses real
        // bold as a numeric font-weight on a span. Reading either one naively ruins the paste.
        let html = r#"<b style="font-weight:normal" id="docs-internal-guid-1">
            <p><span style="font-weight:400">plain and </span><span style="font-weight:700">bold</span></p>
        </b>"#;
        assert_eq!(convert(html), "plain and **bold**\n");
    }

    #[test]
    fn google_docs_italics_and_strikethrough_are_styles_too() {
        let html = r#"<p><span style="font-style:italic">slanted</span> and
            <span style="text-decoration:line-through">struck</span></p>"#;
        assert_eq!(convert(html), "*slanted* and ~~struck~~\n");
    }

    #[test]
    fn styled_runs_that_include_their_trailing_space_still_parse() {
        // `** bold **` is not emphasis anywhere. A word processor produces this shape constantly,
        // because the styled run includes the space before the next word.
        let html = r#"<p><span style="font-weight:bold">bold </span>after</p>"#;
        assert_eq!(convert(html), "**bold** after\n");
    }

    #[test]
    fn word_list_paragraphs_become_a_real_nested_list() {
        let html = r#"
            <p class=MsoListParagraph style='mso-list:l0 level1 lfo1'>
              <span style='mso-list:Ignore'>&middot;<span>&nbsp;</span></span>first</p>
            <p class=MsoListParagraph style='mso-list:l0 level2 lfo1'>
              <span style='mso-list:Ignore'>o<span>&nbsp;</span></span>nested</p>
            <p class=MsoListParagraph style='mso-list:l0 level1 lfo1'>
              <span style='mso-list:Ignore'>&middot;<span>&nbsp;</span></span>second</p>
        "#;
        assert_eq!(convert(html), "- first\n  - nested\n- second\n");
    }

    #[test]
    fn a_numbered_word_list_is_recognised_by_its_marker() {
        let html = r#"
            <p style='mso-list:l0 level1 lfo1'><span style='mso-list:Ignore'>1.</span>one</p>
            <p style='mso-list:l0 level1 lfo1'><span style='mso-list:Ignore'>2.</span>two</p>
        "#;
        assert_eq!(convert(html), "1. one\n2. two\n");
    }

    #[test]
    fn word_paragraphs_around_a_list_are_not_swallowed_by_it() {
        let html = r#"
            <p>Before.</p>
            <p style='mso-list:l0 level1 lfo1'><span style='mso-list:Ignore'>&middot;</span>item</p>
            <p>After.</p>
        "#;
        assert_eq!(convert(html), "Before.\n\n- item\n\nAfter.\n");
    }

    #[test]
    fn nested_lists_indent_under_their_parent_item() {
        let html = "<ul><li>outer<ul><li>inner</li></ul></li><li>next</li></ul>";
        assert_eq!(convert(html), "- outer\n  - inner\n- next\n");
    }

    #[test]
    fn an_ordered_list_keeps_its_start_attribute() {
        assert_eq!(
            convert("<ol start=\"3\"><li>three</li><li>four</li></ol>"),
            "3. three\n4. four\n",
        );
    }

    #[test]
    fn a_checklist_stays_a_checklist() {
        let html = r#"<ul>
            <li><input type="checkbox" checked disabled> done</li>
            <li><input type="checkbox" disabled> todo</li>
        </ul>"#;
        assert_eq!(convert(html), "- [x] done\n- [ ] todo\n");
    }

    #[test]
    fn tables_become_gfm_tables() {
        let html = r#"<table>
            <thead><tr><th>Name</th><th>Value</th></tr></thead>
            <tbody><tr><td>a</td><td>1</td></tr></tbody>
        </table>"#;
        assert_eq!(
            convert(html),
            "| Name | Value |\n| --- | --- |\n| a | 1 |\n",
        );
    }

    #[test]
    fn a_headerless_table_gets_an_empty_header_rather_than_losing_a_row() {
        let html = "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>";
        assert_eq!(
            convert(html),
            "|  |  |\n| --- | --- |\n| a | b |\n| c | d |\n"
        );
    }

    #[test]
    fn a_pipe_inside_a_cell_is_escaped() {
        let html = "<table><tr><th>h</th></tr><tr><td>a|b</td></tr></table>";
        assert!(convert(html).contains("a\\|b"));
    }

    #[test]
    fn code_blocks_keep_their_language_and_their_whitespace() {
        let html = r#"<pre><code class="language-rust">fn main() {
    println!("hi");
}
</code></pre>"#;
        assert_eq!(
            convert(html),
            "```rust\nfn main() {\n    println!(\"hi\");\n}\n```\n",
        );
    }

    #[test]
    fn a_code_block_containing_backticks_gets_a_longer_fence() {
        let html = "<pre><code>a ``` b</code></pre>";
        let markdown = convert(html);
        assert!(markdown.starts_with("````\n"), "got: {markdown}");
        assert!(markdown.trim_end().ends_with("````"));
    }

    #[test]
    fn an_inline_code_span_containing_a_backtick_is_fenced_wider() {
        assert_eq!(convert("<p><code>a`b</code></p>"), "``a`b``\n");
    }

    #[test]
    fn blockquotes_prefix_every_line_they_contain() {
        let html = "<blockquote><p>one</p><p>two</p></blockquote>";
        assert_eq!(convert(html), "> one\n>\n> two\n");
    }

    #[test]
    fn scripts_styles_and_word_noise_are_dropped() {
        let html = r#"<div><style>p{color:red}</style><script>alert(1)</script>
            <o:p></o:p><p>kept</p></div>"#;
        assert_eq!(convert(html), "kept\n");
    }

    #[test]
    fn non_breaking_spaces_become_ordinary_ones() {
        // A document full of U+00A0 looks fine and behaves nothing like text: it does not wrap and
        // the space bar will not find it.
        let markdown = convert("<p>a&nbsp;&nbsp;b</p>");
        assert_eq!(markdown, "a b\n");
        assert!(!markdown.contains('\u{a0}'));
    }

    #[test]
    fn markdown_syntax_in_the_source_text_is_escaped() {
        assert_eq!(convert("<p>2 * 3 * 4</p>"), "2 \\* 3 \\* 4\n");
        assert_eq!(convert("<p>a [link] here</p>"), "a \\[link\\] here\n");
        assert_eq!(convert("<p># not a heading</p>"), "\\# not a heading\n");
    }

    #[test]
    fn escaping_stays_out_of_the_way_where_it_is_not_needed() {
        // Over-escaping is the other failure: `snake\_case` and `1\. two` are correct Markdown and
        // look like a broken tool.
        assert_eq!(convert("<p>snake_case_name</p>"), "snake_case_name\n");
        assert_eq!(
            convert("<p>well-known state-of-the-art</p>"),
            "well-known state-of-the-art\n"
        );
        assert_eq!(convert("<p>5 > 3 and 2 < 4</p>"), "5 > 3 and 2 < 4\n");
    }

    #[test]
    fn images_keep_their_alt_text_and_destination() {
        assert_eq!(
            convert(r#"<p><img src="a.png" alt="A cat"></p>"#),
            "![A cat](a.png)\n",
        );
    }

    #[test]
    fn a_url_with_spaces_is_wrapped_rather_than_broken() {
        let markdown = convert(r#"<a href="/my file.png">x</a>"#);
        assert!(
            markdown.contains("(<%2F") || markdown.contains("(</my%20file.png>"),
            "{markdown}"
        );
    }

    #[test]
    fn anchors_without_a_destination_keep_only_their_text() {
        assert_eq!(convert(r##"<p><a href="#top">back</a></p>"##), "back\n");
        assert_eq!(
            convert(r#"<p><a href="javascript:alert(1)">click</a></p>"#),
            "click\n",
        );
    }

    #[test]
    fn tags_gfm_has_no_syntax_for_are_kept_as_html() {
        assert_eq!(convert("<p>x<sup>2</sup></p>"), "x<sup>2</sup>\n");
        assert_eq!(convert("<p><mark>note</mark></p>"), "<mark>note</mark>\n");
    }

    #[test]
    fn line_breaks_survive_as_line_breaks() {
        assert_eq!(convert("<p>one<br>two</p>"), "one\\\ntwo\n");
    }

    #[test]
    fn an_empty_fragment_converts_to_nothing() {
        assert_eq!(convert(""), "");
        assert_eq!(convert("<div></div>"), "");
        assert_eq!(convert("   \n  "), "");
    }

    #[test]
    fn korean_and_emoji_survive_unchanged() {
        assert_eq!(convert("<p>한국어 문서 🪶</p>"), "한국어 문서 🪶\n");
    }

    #[test]
    fn a_whole_page_paste_drops_the_document_head() {
        let html = r#"<html><head><title>Page</title><meta charset="utf-8"></head>
            <body><h1>Body</h1></body></html>"#;
        assert_eq!(convert(html), "# Body\n");
    }
}
