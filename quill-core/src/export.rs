//! HTML export.
//!
//! Produces a self-contained document with the stylesheet inlined, so an exported file can be
//! mailed or committed without dragging assets along.

use crate::document::Document;
use crate::parser::to_html;
use crate::wire::{Encoder, PayloadKind};

/// Bit flags accepted by the FFI `options` argument.
pub mod options {
    /// Wrap the rendered body in a full HTML document with an inline stylesheet.
    pub const STANDALONE: u32 = 1 << 0;
    /// Use the dark (Darcula) palette rather than the light one.
    pub const DARK: u32 = 1 << 1;
    /// Pass raw HTML in the source through instead of escaping it.
    pub const ALLOW_RAW_HTML: u32 = 1 << 2;
}

const LIGHT_CSS: &str = r#"
:root {
  --quill-bg: #ffffff; --quill-fg: #000000; --quill-muted: #6c707e;
  --quill-border: #ebecf0; --quill-code-bg: #f7f8fa; --quill-link: #2470b3;
  --quill-quote-border: #c9ccd6;
}
"#;

const DARK_CSS: &str = r#"
:root {
  --quill-bg: #1e1f22; --quill-fg: #bcbec4; --quill-muted: #7a7e85;
  --quill-border: #393b40; --quill-code-bg: #2b2d30; --quill-link: #548af7;
  --quill-quote-border: #4e5157;
}
"#;

/// Typography deliberately mirrors the in-app preview (Inter for prose, JetBrains Mono for code) so
/// an exported file looks like what the author saw while writing it.
const BASE_CSS: &str = r#"
* { box-sizing: border-box; }
body {
  margin: 0; padding: 2.5rem 1.5rem;
  background: var(--quill-bg); color: var(--quill-fg);
  font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
               "Helvetica Neue", "Malgun Gothic", "Apple SD Gothic Neo", sans-serif;
  font-size: 15px; line-height: 1.6;
}
main { max-width: 46rem; margin: 0 auto; }
h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.8em 0 0.6em; font-weight: 600; }
h1 { font-size: 2em; } h2 { font-size: 1.5em; } h3 { font-size: 1.25em; }
h1, h2 { padding-bottom: 0.3em; border-bottom: 1px solid var(--quill-border); }
p, ul, ol, blockquote, table, pre { margin: 0 0 1em; }
a { color: var(--quill-link); text-decoration: none; }
a:hover { text-decoration: underline; }
code, pre, kbd, samp {
  font-family: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.9em;
}
code { background: var(--quill-code-bg); padding: 0.15em 0.35em; border-radius: 4px; }
pre { background: var(--quill-code-bg); padding: 1em; border-radius: 6px; overflow-x: auto; }
pre code { background: none; padding: 0; }
blockquote {
  margin-left: 0; padding: 0.2em 1em; color: var(--quill-muted);
  border-left: 3px solid var(--quill-quote-border);
}
table { border-collapse: collapse; width: 100%; display: block; overflow-x: auto; }
th, td { border: 1px solid var(--quill-border); padding: 0.5em 0.75em; text-align: left; }
th { background: var(--quill-code-bg); font-weight: 600; }
hr { border: none; border-top: 1px solid var(--quill-border); margin: 2em 0; }
img { max-width: 100%; height: auto; }
ul, ol { padding-left: 1.6em; }
li { margin: 0.25em 0; }
li input[type="checkbox"] { margin-right: 0.4em; }
"#;

/// Escapes text for insertion into an HTML element or attribute.
fn escape(text: &str) -> String {
    let mut output = String::with_capacity(text.len());
    for character in text.chars() {
        match character {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '"' => output.push_str("&quot;"),
            '\'' => output.push_str("&#39;"),
            other => output.push(other),
        }
    }
    output
}

/// Renders a document to HTML.
pub fn to_html_document(text: &str, title: &str, flags: u32) -> String {
    let body = to_html(text, flags & options::ALLOW_RAW_HTML != 0);
    if flags & options::STANDALONE == 0 {
        return body;
    }

    let palette = if flags & options::DARK != 0 { DARK_CSS } else { LIGHT_CSS };
    format!(
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n\
         <meta name=\"generator\" content=\"Quill\">\n<title>{}</title>\n\
         <style>{}{}</style>\n</head>\n<body>\n<main>\n{}</main>\n</body>\n</html>\n",
        escape(title),
        palette,
        BASE_CSS,
        body,
    )
}

/// Encodes exported HTML as a [`PayloadKind::Text`] payload.
pub fn encode(document: &mut Document, title: &str, flags: u32) -> Vec<u8> {
    let html = to_html_document(document.text(), title, flags);
    let mut encoder = Encoder::new(PayloadKind::Text);
    encoder.put_str(&html);
    encoder.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::Decoder;

    #[test]
    fn fragment_mode_returns_bare_html() {
        let html = to_html_document("# Hi\n", "Title", 0);
        assert!(html.contains("<h1>Hi</h1>"));
        assert!(!html.contains("<!doctype html>"));
    }

    #[test]
    fn standalone_mode_produces_a_complete_document() {
        let html = to_html_document("# Hi\n", "My Doc", options::STANDALONE);
        assert!(html.starts_with("<!doctype html>"));
        assert!(html.contains("<title>My Doc</title>"));
        assert!(html.contains("</html>"));
        assert!(html.contains("--quill-bg: #ffffff"), "light palette by default");
    }

    #[test]
    fn dark_flag_selects_the_dark_palette() {
        let html = to_html_document("x\n", "t", options::STANDALONE | options::DARK);
        assert!(html.contains("--quill-bg: #1e1f22"));
        assert!(!html.contains("--quill-bg: #ffffff"));
    }

    #[test]
    fn escapes_the_title() {
        // A document named after an HTML tag must not be able to inject markup into the head.
        let html = to_html_document("x\n", "<script>alert(1)</script>", options::STANDALONE);
        assert!(html.contains("&lt;script&gt;"));
        assert!(!html.contains("<title><script>"));
    }

    #[test]
    fn escapes_raw_html_in_the_body_by_default() {
        let html = to_html_document("<img src=x onerror=alert(1)>\n", "t", options::STANDALONE);
        assert!(!html.contains("onerror=alert(1)>"), "raw HTML must be escaped unless opted in");
        assert!(to_html_document("<b>bold</b>\n", "t", options::ALLOW_RAW_HTML).contains("<b>bold</b>"));
    }

    #[test]
    fn renders_gfm_tables_and_task_lists() {
        let html = to_html_document("| a |\n|---|\n| 1 |\n\n- [x] done\n", "t", 0);
        assert!(html.contains("<table>"));
        assert!(html.contains("type=\"checkbox\""));
    }

    #[test]
    fn escape_covers_all_five_entities() {
        assert_eq!(escape("&<>\"'"), "&amp;&lt;&gt;&quot;&#39;");
    }

    #[test]
    fn preserves_non_ascii_content() {
        let html = to_html_document("# 한국어 제목 🪶\n", "제목", options::STANDALONE);
        assert!(html.contains("한국어 제목 🪶"));
        assert!(html.contains("charset=\"utf-8\""));
    }

    #[test]
    fn encodes_html_as_a_text_payload() {
        let mut document = Document::new("# Title\n");
        let bytes = encode(&mut document, "Doc", options::STANDALONE);
        let (mut decoder, kind) = Decoder::new(&bytes).unwrap();
        assert_eq!(kind, PayloadKind::Text);
        assert!(decoder.string().unwrap().contains("<h1>Title</h1>"));
        assert!(decoder.is_exhausted());
    }
}
