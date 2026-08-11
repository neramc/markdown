//! HTML generation and the DOM the preview renders from.
//!
//! The preview is produced by converting Markdown to HTML and then rendering that HTML, rather than
//! by rendering the Markdown AST directly. That extra step buys two things worth its cost:
//!
//! * **Raw HTML in the source is rendered rather than shown.** A document containing `<kbd>` or a
//!   hand-written table used to reach the preview as literal text; now it goes through the same path
//!   as everything else.
//! * **The preview and the exported file are the same document.** Export already produced HTML, so
//!   two renderers existed with two sets of quirks; a heading that looked one way on screen and
//!   another in the export was a bug nobody could see until they published.
//!
//! The parser below is small and deliberately tolerant. It handles the subset of HTML that Markdown
//! conversion emits plus the raw fragments people actually write inline, and it never fails: an
//! unclosed tag closes at its parent's end rather than aborting the render, because a preview that
//! disappears while you are mid-sentence is worse than one that is briefly wrong.

use crate::wire::{Encoder, PayloadKind};

/// A node in the rendered document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Node {
    /// Character data, already entity-decoded.
    Text(String),
    /// An element with its attributes and children.
    Element(Element),
}

/// An HTML element.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Element {
    /// Lowercase tag name.
    pub tag: String,
    /// Attributes, in source order, with values entity-decoded.
    pub attributes: Vec<(String, String)>,
    /// Child nodes.
    pub children: Vec<Node>,
}

impl Element {
    /// Returns an attribute's value.
    pub fn attribute(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }
}

/// Elements that never have children and never need closing.
const VOID_ELEMENTS: &[&str] = &[
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source",
    "track", "wbr",
];

/// Elements whose content is text rather than markup.
const RAW_TEXT_ELEMENTS: &[&str] = &["script", "style", "textarea", "title"];

/// How deeply elements may nest before the parser stops descending.
///
/// Both the parser and the encoder walk the tree recursively, as does dropping it, so nesting depth
/// is stack depth. Nothing a person writes comes close to this, but the preview re-parses on every
/// keystroke and MDX passes raw HTML straight through, so a document containing a few thousand
/// unclosed `<div>`s would otherwise overflow the native stack — a hard crash of the whole editor,
/// from typing. Past the limit the parser stops descending and the content becomes siblings instead
/// of children: still wrong, but wrong in the same way the rest of this parser is wrong for
/// malformed input, and recoverable by deleting a character.
const MAX_DEPTH: usize = 256;

/// Parses an HTML fragment into a node tree.
///
/// Never fails. Malformed input degrades to text rather than to an error, which is the right
/// trade-off for something re-run on every keystroke.
pub fn parse(html: &str) -> Vec<Node> {
    Parser {
        bytes: html.as_bytes(),
        input: html,
        position: 0,
        depth: 0,
    }
    .parse_nodes(None)
}

struct Parser<'a> {
    bytes: &'a [u8],
    input: &'a str,
    position: usize,
    depth: usize,
}

impl<'a> Parser<'a> {
    /// Parses children until the document ends or `parent`'s closing tag is reached.
    fn parse_nodes(&mut self, parent: Option<&str>) -> Vec<Node> {
        let mut nodes = Vec::new();
        let mut text = String::new();

        while self.position < self.bytes.len() {
            if self.bytes[self.position] == b'<' {
                // A closing tag for our parent ends this level.
                if let Some(name) = self.peek_closing_tag() {
                    if let Some(parent) = parent {
                        if name == parent {
                            self.skip_closing_tag();
                            break;
                        }
                    }
                    // A closing tag for something else is stray; consume it so it cannot loop.
                    self.skip_closing_tag();
                    continue;
                }

                if self.peek_comment() {
                    self.skip_comment();
                    continue;
                }

                if let Some((element, self_closing)) = self.parse_open_tag() {
                    push_text(&mut nodes, &mut text);

                    let tag = element.tag.clone();
                    let mut element = element;

                    if !self_closing && !VOID_ELEMENTS.contains(&tag.as_str()) {
                        element.children = if RAW_TEXT_ELEMENTS.contains(&tag.as_str()) {
                            let raw = self.take_raw_text(&tag);
                            if raw.is_empty() {
                                Vec::new()
                            } else {
                                vec![Node::Text(raw)]
                            }
                        } else if self.depth >= MAX_DEPTH {
                            // Stop descending. The element's content is parsed at this level
                            // instead, so it survives as siblings rather than costing a stack frame.
                            Vec::new()
                        } else {
                            self.depth += 1;
                            let children = self.parse_nodes(Some(&tag));
                            self.depth -= 1;
                            children
                        };
                    }

                    nodes.push(Node::Element(element));
                    continue;
                }

                // A '<' that starts nothing recognisable is literal text.
                text.push('<');
                self.position += 1;
                continue;
            }

            let next = self.input[self.position..]
                .find('<')
                .map_or(self.bytes.len(), |index| self.position + index);
            text.push_str(&decode_entities(&self.input[self.position..next]));
            self.position = next;
        }

        push_text(&mut nodes, &mut text);
        nodes
    }

    fn peek_closing_tag(&self) -> Option<String> {
        let rest = self.input.get(self.position..)?;
        let inner = rest.strip_prefix("</")?;
        let end = inner.find('>')?;
        Some(inner[..end].trim().to_ascii_lowercase())
    }

    fn skip_closing_tag(&mut self) {
        if let Some(rest) = self.input.get(self.position..) {
            if let Some(end) = rest.find('>') {
                self.position += end + 1;
                return;
            }
        }
        self.position = self.bytes.len();
    }

    fn peek_comment(&self) -> bool {
        self.input[self.position..].starts_with("<!")
    }

    fn skip_comment(&mut self) {
        let rest = &self.input[self.position..];
        if let Some(end) = rest.find("-->") {
            self.position += end + 3;
        } else if let Some(end) = rest.find('>') {
            self.position += end + 1;
        } else {
            self.position = self.bytes.len();
        }
    }

    /// Parses `<tag attr="value">`, returning the element and whether it self-closed.
    fn parse_open_tag(&mut self) -> Option<(Element, bool)> {
        let rest = self.input.get(self.position..)?;
        if !rest.starts_with('<') {
            return None;
        }

        let after = &rest[1..];
        let first = after.chars().next()?;
        if !first.is_ascii_alphabetic() {
            return None;
        }

        let end = find_tag_end(after)?;
        let inner = &after[..end];
        self.position += 1 + end + 1;

        let self_closing = inner.trim_end().ends_with('/');
        let inner = inner.trim_end().trim_end_matches('/');

        let mut parts = inner.splitn(2, |c: char| c.is_whitespace());
        let tag = parts.next()?.to_ascii_lowercase();
        let attributes = parts.next().map(parse_attributes).unwrap_or_default();

        Some((
            Element {
                tag,
                attributes,
                children: Vec::new(),
            },
            self_closing,
        ))
    }

    /// Consumes everything up to `</tag>` without interpreting it.
    fn take_raw_text(&mut self, tag: &str) -> String {
        let closing = format!("</{tag}");
        let rest = &self.input[self.position..];
        match rest.to_ascii_lowercase().find(&closing) {
            Some(index) => {
                let text = rest[..index].to_owned();
                self.position += index;
                self.skip_closing_tag();
                text
            }
            None => {
                let text = rest.to_owned();
                self.position = self.bytes.len();
                text
            }
        }
    }
}

/// Finds the `>` that ends a tag, ignoring any inside quoted attribute values.
fn find_tag_end(input: &str) -> Option<usize> {
    let mut quote: Option<char> = None;
    for (index, character) in input.char_indices() {
        match quote {
            Some(open) if character == open => quote = None,
            Some(_) => {}
            None => match character {
                '"' | '\'' => quote = Some(character),
                '>' => return Some(index),
                _ => {}
            },
        }
    }
    None
}

/// Parses an attribute list into name/value pairs.
fn parse_attributes(input: &str) -> Vec<(String, String)> {
    let mut attributes = Vec::new();
    let bytes = input.as_bytes();
    let mut index = 0usize;

    while index < bytes.len() {
        while index < bytes.len() && (bytes[index] as char).is_whitespace() {
            index += 1;
        }
        if index >= bytes.len() {
            break;
        }

        let name_start = index;
        while index < bytes.len() {
            let character = bytes[index] as char;
            if character.is_whitespace() || character == '=' {
                break;
            }
            index += 1;
        }
        if index == name_start {
            index += 1;
            continue;
        }

        let name = input[name_start..index].to_ascii_lowercase();

        while index < bytes.len() && (bytes[index] as char).is_whitespace() {
            index += 1;
        }

        // A bare attribute (`disabled`) has its own name as its value, which is what HTML says.
        if index >= bytes.len() || bytes[index] != b'=' {
            attributes.push((name.clone(), name));
            continue;
        }
        index += 1;

        while index < bytes.len() && (bytes[index] as char).is_whitespace() {
            index += 1;
        }
        if index >= bytes.len() {
            attributes.push((name.clone(), String::new()));
            break;
        }

        let value = match bytes[index] {
            quote @ (b'"' | b'\'') => {
                index += 1;
                let start = index;
                while index < bytes.len() && bytes[index] != quote {
                    index += 1;
                }
                let raw = &input[start..index.min(input.len())];
                index = (index + 1).min(bytes.len());
                raw
            }
            _ => {
                let start = index;
                while index < bytes.len() && !(bytes[index] as char).is_whitespace() {
                    index += 1;
                }
                &input[start..index]
            }
        };

        attributes.push((name, decode_entities(value)));
    }

    attributes
}

/// Decodes the entities Markdown conversion emits.
///
/// Deliberately not the full named-entity table: comrak escapes exactly five characters, and raw
/// HTML in a Markdown file rarely uses more than a handful more. Anything unrecognised is left
/// alone, which shows the entity rather than dropping the text.
fn decode_entities(input: &str) -> String {
    if !input.contains('&') {
        return input.to_owned();
    }

    let mut out = String::with_capacity(input.len());
    let mut rest = input;

    while let Some(index) = rest.find('&') {
        out.push_str(&rest[..index]);
        rest = &rest[index..];

        let Some(end) = rest.find(';').filter(|end| *end <= 10) else {
            out.push('&');
            rest = &rest[1..];
            continue;
        };

        let entity = &rest[1..end];
        let decoded = match entity {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" | "#39" => Some('\''),
            "nbsp" => Some('\u{a0}'),
            "hellip" => Some('\u{2026}'),
            "mdash" => Some('\u{2014}'),
            "ndash" => Some('\u{2013}'),
            _ => entity
                .strip_prefix('#')
                .and_then(|number| {
                    number
                        .strip_prefix('x')
                        .or_else(|| number.strip_prefix('X'))
                        .and_then(|hex| u32::from_str_radix(hex, 16).ok())
                        .or_else(|| number.parse::<u32>().ok())
                })
                .and_then(char::from_u32),
        };

        match decoded {
            Some(character) => {
                out.push(character);
                rest = &rest[end + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }

    out.push_str(rest);
    out
}

fn push_text(nodes: &mut Vec<Node>, text: &mut String) {
    if !text.is_empty() {
        nodes.push(Node::Text(std::mem::take(text)));
    }
}

/// Node tags on the wire.
const NODE_TEXT: u8 = 0;
const NODE_ELEMENT: u8 = 1;

/// Encodes a parsed document as a QWIRE payload.
pub fn encode(nodes: &[Node]) -> Vec<u8> {
    let mut encoder = Encoder::new(PayloadKind::HtmlDom);
    encoder.put_u32(nodes.len() as u32);
    for node in nodes {
        encode_node(&mut encoder, node);
    }
    encoder.finish()
}

fn encode_node(encoder: &mut Encoder, node: &Node) {
    match node {
        Node::Text(text) => {
            encoder.put_u8(NODE_TEXT);
            encoder.put_str(text);
        }
        Node::Element(element) => {
            encoder.put_u8(NODE_ELEMENT);
            encoder.put_str(&element.tag);

            encoder.put_u32(element.attributes.len() as u32);
            for (name, value) in &element.attributes {
                encoder.put_str(name);
                encoder.put_str(value);
            }

            encoder.put_u32(element.children.len() as u32);
            for child in &element.children {
                encode_node(encoder, child);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn element(nodes: &[Node], index: usize) -> &Element {
        match &nodes[index] {
            Node::Element(element) => element,
            other => panic!("expected an element, found {other:?}"),
        }
    }

    fn text_of(node: &Node) -> String {
        match node {
            Node::Text(text) => text.clone(),
            Node::Element(element) => element.children.iter().map(text_of).collect(),
        }
    }

    #[test]
    fn parses_a_heading_and_a_paragraph() {
        let nodes = parse("<h1>Title</h1>\n<p>Body</p>\n");
        let elements: Vec<_> = nodes
            .iter()
            .filter_map(|node| match node {
                Node::Element(element) => Some(element.tag.as_str()),
                Node::Text(_) => None,
            })
            .collect();

        assert_eq!(elements, vec!["h1", "p"]);
        assert_eq!(text_of(&nodes[0]), "Title");
    }

    #[test]
    fn parses_nested_structure() {
        let nodes = parse("<ul><li><p>one</p></li><li>two</li></ul>");
        let list = element(&nodes, 0);

        assert_eq!(list.tag, "ul");
        assert_eq!(list.children.len(), 2);
        assert_eq!(text_of(&list.children[0]), "one");
        assert_eq!(text_of(&list.children[1]), "two");
    }

    #[test]
    fn reads_attributes_in_every_spelling() {
        let nodes =
            parse(r#"<a href="https://example.com" title='hi' download rel=noopener>x</a>"#);
        let anchor = element(&nodes, 0);

        assert_eq!(anchor.attribute("href"), Some("https://example.com"));
        assert_eq!(anchor.attribute("title"), Some("hi"));
        // A bare attribute takes its own name as its value.
        assert_eq!(anchor.attribute("download"), Some("download"));
        assert_eq!(anchor.attribute("rel"), Some("noopener"));
    }

    #[test]
    fn a_greater_than_inside_an_attribute_does_not_end_the_tag() {
        let nodes = parse(r#"<img alt="a > b" src="x.png">after"#);
        let image = element(&nodes, 0);

        assert_eq!(image.tag, "img");
        assert_eq!(image.attribute("alt"), Some("a > b"));
        assert_eq!(text_of(&nodes[1]), "after");
    }

    #[test]
    fn void_elements_take_no_children() {
        let nodes = parse("<p>before<br>after</p>");
        let paragraph = element(&nodes, 0);

        assert_eq!(paragraph.children.len(), 3);
        assert_eq!(text_of(&paragraph.children[0]), "before");
        assert!(matches!(&paragraph.children[1], Node::Element(e) if e.tag == "br"));
        assert_eq!(text_of(&paragraph.children[2]), "after");
    }

    #[test]
    fn self_closing_tags_are_accepted() {
        let nodes = parse("<p>a<br/>b</p>");
        assert_eq!(element(&nodes, 0).children.len(), 3);
    }

    #[test]
    fn entities_are_decoded() {
        let nodes = parse("<p>a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39; &#x41;</p>");
        assert_eq!(text_of(&nodes[0]), "a & b <c> \"d\" 'e' A");
    }

    #[test]
    fn an_unknown_entity_is_left_alone() {
        let nodes = parse("<p>100 &euro; and &notanentity;</p>");
        let text = text_of(&nodes[0]);
        assert!(text.contains("&euro;"), "{text}");
        assert!(text.contains("&notanentity;"), "{text}");
    }

    #[test]
    fn comments_are_dropped() {
        let nodes = parse("<p>a</p><!-- hidden --><p>b</p>");
        let tags: Vec<_> = nodes
            .iter()
            .filter_map(|node| match node {
                Node::Element(element) => Some(element.tag.as_str()),
                Node::Text(_) => None,
            })
            .collect();
        assert_eq!(tags, vec!["p", "p"]);
    }

    #[test]
    fn an_unclosed_tag_closes_at_the_end_rather_than_failing() {
        // This is what a preview sees constantly: the user is mid-sentence and the markup is not
        // finished yet.
        let nodes = parse("<div><p>text");
        let div = element(&nodes, 0);
        assert_eq!(div.tag, "div");
        assert_eq!(text_of(&nodes[0]), "text");
    }

    #[test]
    fn a_stray_closing_tag_does_not_loop() {
        let nodes = parse("</p>text</div>more");
        let combined: String = nodes.iter().map(text_of).collect();
        assert_eq!(combined, "textmore");
    }

    #[test]
    fn a_bare_less_than_is_text() {
        let nodes = parse("<p>a &lt; b and 1 < 2</p>");
        assert!(text_of(&nodes[0]).contains("1 < 2"));
    }

    #[test]
    fn script_content_is_not_parsed_as_markup() {
        let nodes = parse("<script>if (a < b) { x(); }</script><p>after</p>");
        let script = element(&nodes, 0);

        assert_eq!(script.tag, "script");
        assert_eq!(text_of(&nodes[0]), "if (a < b) { x(); }");
        assert_eq!(element(&nodes, 1).tag, "p");
    }

    #[test]
    fn tables_survive_with_their_alignment() {
        let html = r#"<table><thead><tr><th align="right">n</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table>"#;
        let nodes = parse(html);
        let table = element(&nodes, 0);

        assert_eq!(table.tag, "table");
        let head = match &table.children[0] {
            Node::Element(element) => element,
            other => panic!("{other:?}"),
        };
        assert_eq!(head.tag, "thead");
        assert_eq!(text_of(&table.children[1]), "1");
    }

    #[test]
    fn encoding_produces_a_readable_payload() {
        let nodes = parse(r#"<p class="lead">hi</p>"#);
        let bytes = encode(&nodes);

        // The header is checked by the wire tests; here it is enough that the payload is non-empty
        // and carries the tag and attribute text.
        assert!(bytes.len() > 8);
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains('p'));
        assert!(text.contains("class"));
        assert!(text.contains("lead"));
    }

    #[test]
    fn parsing_is_stable_on_pathological_input() {
        // None of these should hang or panic; that is the whole assertion.
        for input in [
            "<",
            "<<<<",
            "<p",
            "<p ",
            r#"<p class="#,
            "</",
            "<!--",
            "<script>",
            "&",
            "&#;",
            "&#xZZ;",
        ] {
            let _ = parse(input);
        }
    }

    #[test]
    fn nesting_beyond_the_depth_limit_does_not_overflow_the_stack() {
        // Without the cap this parses, encodes and drops through one stack frame per level, which
        // overflows long before ten thousand. The document is nonsense, but it is nonsense a person
        // can type into an MDX file, and the preview re-parses on every keystroke.
        let source = "<div>".repeat(10_000);
        let nodes = parse(&source);

        // The tree is still well-formed and still round-trips through the wire format.
        assert_eq!(nodes.len(), 1);
        assert!(!encode(&nodes).is_empty());

        let mut depth = 0;
        let mut current = &nodes[0];
        while let Node::Element(element) = current {
            depth += 1;
            match element.children.first() {
                Some(child) => current = child,
                None => break,
            }
        }
        assert_eq!(depth, MAX_DEPTH + 1, "descent should stop at the limit");
    }

    #[test]
    fn content_below_the_depth_limit_is_kept_as_siblings() {
        let source = format!("{}text", "<div>".repeat(MAX_DEPTH + 4));
        let nodes = parse(&source);

        // The text is past the limit, so it lands beside the deepest element rather than inside it.
        // Losing it entirely would be the worse failure: the preview would silently drop content.
        let rendered: String = nodes.iter().map(text_of).collect();
        assert!(rendered.contains("text"));
    }
}
