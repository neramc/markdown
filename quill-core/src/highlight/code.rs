//! Syntax highlighting for fenced code blocks, backed by syntect.
//!
//! Unlike the editor highlighter this one resolves concrete colours, because syntect works in
//! TextMate scopes and the mapping to IntelliJ's palette lives in [`crate::theme`]. The result feeds
//! Jewel's `CodeHighlighter` seam, so code blocks in the preview look the way they do in the IDE.

use std::sync::LazyLock;

use syntect::easy::HighlightLines;
use syntect::parsing::{SyntaxReference, SyntaxSet};
use syntect::util::LinesWithEndings;

use crate::theme::{ColorSpan, code_theme, default_code_foreground, pack_argb};

/// bat's extended syntax set: the Sublime defaults plus Kotlin, TOML, Dockerfile and friends, which
/// matter a lot for the code fences in a developer's Markdown.
static SYNTAXES: LazyLock<SyntaxSet> = LazyLock::new(two_face::syntax::extra_newlines);

/// Resolves a fence info string to a syntect syntax.
///
/// Markdown authors write `js`, `sh`, `c++`, `Dockerfile` and everything in between, so this tries
/// token, extension and name lookups before giving up.
fn find_syntax(language: &str) -> Option<&'static SyntaxReference> {
    let syntaxes = &*SYNTAXES;
    let trimmed = language.trim();
    if trimmed.is_empty() {
        return None;
    }

    let normalized = trimmed.to_ascii_lowercase();
    let aliased = match normalized.as_str() {
        "kt" | "kts" => "kotlin",
        "rs" => "rust",
        "js" | "mjs" | "cjs" => "javascript",
        "ts" => "typescript",
        "py" => "python",
        "sh" | "zsh" | "shell" => "bash",
        "yml" => "yaml",
        "md" => "markdown",
        "cs" => "c#",
        "cpp" | "cc" | "cxx" | "h" | "hpp" => "c++",
        "docker" => "dockerfile",
        "gradle" => "groovy",
        "objc" => "objective-c",
        "ps1" => "powershell",
        other => other,
    };

    syntaxes
        .find_syntax_by_token(aliased)
        .or_else(|| syntaxes.find_syntax_by_extension(aliased))
        .or_else(|| syntaxes.find_syntax_by_name(trimmed))
}

/// Highlights `code`, returning coloured runs in UTF-16 offsets relative to the start of `code`.
///
/// An unknown language produces a single default-coloured run rather than an error: a fence tagged
/// with something syntect has never heard of should still render as code.
pub fn highlight(code: &str, language: &str, dark: bool) -> Vec<ColorSpan> {
    let fallback = default_code_foreground(dark);
    let total = code.chars().map(char::len_utf16).sum::<usize>();

    let Some(syntax) = find_syntax(language) else {
        return single_run(total, fallback);
    };

    let mut highlighter = HighlightLines::new(syntax, code_theme(dark));
    let mut spans: Vec<ColorSpan> = Vec::new();
    let mut offset = 0usize;

    for line in LinesWithEndings::from(code) {
        // A syntax whose regexes blow up mid-document should not take the preview with it; the
        // remaining lines simply fall back to the default colour.
        let Ok(ranges) = highlighter.highlight_line(line, &SYNTAXES) else {
            if total > offset {
                spans.push(ColorSpan { start: offset, end: total, argb: fallback });
            }
            break;
        };

        for (style, piece) in ranges {
            let width = piece.chars().map(char::len_utf16).sum::<usize>();
            if width == 0 {
                continue;
            }
            let argb = pack_argb(style.foreground);
            // Merging adjacent identical colours keeps the span list short, which matters because
            // the JVM allocates one AnnotatedString range per span.
            match spans.last_mut() {
                Some(last) if last.end == offset && last.argb == argb => last.end += width,
                _ => spans.push(ColorSpan { start: offset, end: offset + width, argb }),
            }
            offset += width;
        }
    }

    if spans.is_empty() { single_run(total, fallback) } else { spans }
}

fn single_run(total: usize, argb: u32) -> Vec<ColorSpan> {
    if total == 0 { Vec::new() } else { vec![ColorSpan { start: 0, end: total, argb }] }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_common_languages_and_aliases() {
        for language in ["rust", "rs", "python", "js", "yaml", "json"] {
            assert!(find_syntax(language).is_some(), "{language} should resolve");
        }
    }

    #[test]
    fn unknown_and_blank_languages_resolve_to_nothing() {
        assert!(find_syntax("definitely-not-a-language").is_none());
        assert!(find_syntax("").is_none());
        assert!(find_syntax("   ").is_none());
    }

    #[test]
    fn highlights_rust_into_multiple_colours() {
        let spans = highlight("fn main() {\n    let x = 1;\n}\n", "rust", true);
        assert!(spans.len() > 1, "expected more than one coloured run");
        let distinct: std::collections::BTreeSet<u32> = spans.iter().map(|span| span.argb).collect();
        assert!(distinct.len() > 1, "keywords and identifiers should differ in colour");
    }

    #[test]
    fn spans_are_contiguous_and_cover_the_input() {
        let code = "let value = \"text\"; // comment\n";
        let spans = highlight(code, "rust", true);
        let expected: usize = code.chars().map(char::len_utf16).sum();

        assert_eq!(spans.first().unwrap().start, 0);
        assert_eq!(spans.last().unwrap().end, expected);
        for pair in spans.windows(2) {
            assert_eq!(pair[0].end, pair[1].start, "spans must not overlap or leave gaps");
        }
    }

    #[test]
    fn unknown_language_still_produces_one_run() {
        let spans = highlight("some text", "brainmelt", true);
        assert_eq!(spans.len(), 1);
        assert_eq!((spans[0].start, spans[0].end), (0, 9));
        assert_eq!(spans[0].argb, default_code_foreground(true));
    }

    #[test]
    fn empty_code_produces_no_spans() {
        assert!(highlight("", "rust", true).is_empty());
        assert!(highlight("", "unknown", false).is_empty());
    }

    #[test]
    fn offsets_are_utf16_for_non_ascii_code() {
        // A string literal containing Korean: 9 UTF-8 bytes but 3 UTF-16 units.
        let code = "let s = \"한국어\";\n";
        let spans = highlight(code, "rust", true);
        let expected: usize = code.chars().map(char::len_utf16).sum();
        assert_eq!(spans.last().unwrap().end, expected);
    }

    #[test]
    fn light_and_dark_themes_differ() {
        let dark = highlight("fn main() {}", "rust", true);
        let light = highlight("fn main() {}", "rust", false);
        assert_ne!(dark[0].argb, light[0].argb);
    }
}
