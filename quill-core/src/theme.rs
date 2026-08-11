//! Semantic style identifiers and the syntax-highlighting colour schemes.
//!
//! Two different colouring concerns live here:
//!
//! * [`EditorStyle`] — semantic token kinds for the *Markdown source* shown in the editor. The
//!   engine emits only the identifier; the Kotlin side resolves it against the active Jewel theme,
//!   so editor colours track the IDE theme without the engine recomputing a single span.
//! * [`code_theme`] — concrete IntelliJ colour schemes for *fenced code blocks*, where the engine
//!   resolves colours itself because syntect works in terms of TextMate scopes.

use std::str::FromStr;
use std::sync::LazyLock;

use syntect::highlighting::{
    Color, FontStyle, ScopeSelectors, StyleModifier, Theme, ThemeItem, ThemeSettings,
};

/// Semantic token kinds for Markdown source text.
///
/// The numeric values are a wire contract with `EditorStyleId` on the JVM side; append new variants
/// at the end rather than renumbering.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u32)]
pub enum EditorStyle {
    Text = 0,
    Heading = 1,
    Emphasis = 2,
    Strong = 3,
    InlineCode = 4,
    CodeFence = 5,
    CodeFenceInfo = 6,
    LinkText = 7,
    LinkUrl = 8,
    ListMarker = 9,
    BlockQuote = 10,
    ThematicBreak = 11,
    HtmlTag = 12,
    TableDelimiter = 13,
    TaskMarker = 14,
    FrontMatter = 15,
    Strikethrough = 16,
    Image = 17,
    FootnoteReference = 18,
    AutoLink = 19,
}

impl EditorStyle {
    pub fn id(self) -> u32 {
        self as u32
    }
}

/// A styled run of source text, expressed in UTF-16 code units to match the JVM.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StyleSpan {
    pub start: usize,
    pub end: usize,
    pub style: EditorStyle,
}

impl StyleSpan {
    pub fn new(start: usize, end: usize, style: EditorStyle) -> Option<Self> {
        // Zero-width and inverted spans are dropped at construction: they cannot be rendered and
        // would otherwise reach the JVM as confusing no-op annotations.
        if end > start {
            Some(Self { start, end, style })
        } else {
            None
        }
    }
}

/// A coloured run inside a fenced code block. `argb` is a packed 0xAARRGGBB value.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColorSpan {
    pub start: usize,
    pub end: usize,
    pub argb: u32,
}

const fn rgb(hex: u32) -> Color {
    Color {
        r: ((hex >> 16) & 0xFF) as u8,
        g: ((hex >> 8) & 0xFF) as u8,
        b: (hex & 0xFF) as u8,
        a: 0xFF,
    }
}

/// Packs a syntect colour into 0xAARRGGBB, which is what `androidx.compose.ui.graphics.Color`
/// expects on the other side of the bridge.
pub fn pack_argb(color: Color) -> u32 {
    (u32::from(color.a) << 24)
        | (u32::from(color.r) << 16)
        | (u32::from(color.g) << 8)
        | u32::from(color.b)
}

/// Scope-to-colour rules, expressed the way a `.tmTheme` would.
///
/// Building the theme in code rather than shipping a `.tmTheme` keeps the code-block palette exactly
/// on IntelliJ's Darcula / Light schemes instead of an approximate third-party theme.
struct Rule {
    selector: &'static str,
    color: u32,
    font: Option<FontStyle>,
}

const DARCULA_FOREGROUND: u32 = 0x00A9_B7C6;
const DARCULA_BACKGROUND: u32 = 0x002B_2D30;
const LIGHT_FOREGROUND: u32 = 0x0000_0000;
const LIGHT_BACKGROUND: u32 = 0x00FF_FFFF;

/// IntelliJ Darcula.
const DARCULA_RULES: &[Rule] = &[
    Rule {
        selector: "comment",
        color: 0x0080_8080,
        font: Some(FontStyle::ITALIC),
    },
    Rule {
        selector: "string, string.quoted",
        color: 0x006A_8759,
        font: None,
    },
    Rule {
        selector: "constant.character.escape",
        color: 0x00CC_7832,
        font: None,
    },
    Rule {
        selector: "constant.numeric",
        color: 0x0068_97BB,
        font: None,
    },
    Rule {
        selector: "constant.language",
        color: 0x00CC_7832,
        font: None,
    },
    Rule {
        selector: "constant.other",
        color: 0x0098_76AA,
        font: None,
    },
    Rule {
        selector: "keyword, storage.type, storage.modifier",
        color: 0x00CC_7832,
        font: None,
    },
    Rule {
        selector: "keyword.operator",
        color: 0x00A9_B7C6,
        font: None,
    },
    Rule {
        selector: "entity.name.function, support.function",
        color: 0x00FF_C66D,
        font: None,
    },
    Rule {
        selector: "entity.name.type, entity.name.class, support.class",
        color: 0x00A9_B7C6,
        font: None,
    },
    Rule {
        selector: "entity.name.tag",
        color: 0x00E8_BF6A,
        font: None,
    },
    Rule {
        selector: "entity.other.attribute-name",
        color: 0x00BA_BABA,
        font: None,
    },
    Rule {
        selector: "variable.parameter",
        color: 0x00A9_B7C6,
        font: None,
    },
    Rule {
        selector: "variable.language",
        color: 0x0094_558D,
        font: None,
    },
    Rule {
        selector: "meta.annotation, punctuation.definition.annotation",
        color: 0x00BB_B529,
        font: None,
    },
    Rule {
        selector: "invalid",
        color: 0x00FF_6B68,
        font: None,
    },
    Rule {
        selector: "markup.heading",
        color: 0x00FF_C66D,
        font: Some(FontStyle::BOLD),
    },
    Rule {
        selector: "markup.inserted",
        color: 0x006A_8759,
        font: None,
    },
    Rule {
        selector: "markup.deleted",
        color: 0x00CC_7832,
        font: None,
    },
];

/// IntelliJ Light.
const LIGHT_RULES: &[Rule] = &[
    Rule {
        selector: "comment",
        color: 0x008C_8C8C,
        font: Some(FontStyle::ITALIC),
    },
    Rule {
        selector: "string, string.quoted",
        color: 0x0006_7D17,
        font: None,
    },
    Rule {
        selector: "constant.character.escape",
        color: 0x0000_37A6,
        font: None,
    },
    Rule {
        selector: "constant.numeric",
        color: 0x0017_50EB,
        font: None,
    },
    Rule {
        selector: "constant.language",
        color: 0x0000_33B3,
        font: Some(FontStyle::BOLD),
    },
    Rule {
        selector: "constant.other",
        color: 0x0087_1094,
        font: None,
    },
    Rule {
        selector: "keyword, storage.type, storage.modifier",
        color: 0x0000_33B3,
        font: Some(FontStyle::BOLD),
    },
    Rule {
        selector: "keyword.operator",
        color: 0x0000_0000,
        font: None,
    },
    Rule {
        selector: "entity.name.function, support.function",
        color: 0x0000_627A,
        font: None,
    },
    Rule {
        selector: "entity.name.type, entity.name.class, support.class",
        color: 0x0000_0000,
        font: None,
    },
    Rule {
        selector: "entity.name.tag",
        color: 0x0000_0080,
        font: Some(FontStyle::BOLD),
    },
    Rule {
        selector: "entity.other.attribute-name",
        color: 0x0017_50EB,
        font: None,
    },
    Rule {
        selector: "variable.parameter",
        color: 0x0000_0000,
        font: None,
    },
    Rule {
        selector: "variable.language",
        color: 0x0087_1094,
        font: None,
    },
    Rule {
        selector: "meta.annotation, punctuation.definition.annotation",
        color: 0x009E_880D,
        font: None,
    },
    Rule {
        selector: "invalid",
        color: 0x00FF_0000,
        font: None,
    },
    Rule {
        selector: "markup.heading",
        color: 0x0000_627A,
        font: Some(FontStyle::BOLD),
    },
    Rule {
        selector: "markup.inserted",
        color: 0x0006_7D17,
        font: None,
    },
    Rule {
        selector: "markup.deleted",
        color: 0x00A3_1515,
        font: None,
    },
];

fn build_theme(name: &str, foreground: u32, background: u32, rules: &[Rule]) -> Theme {
    let settings = ThemeSettings {
        foreground: Some(rgb(foreground)),
        background: Some(rgb(background)),
        ..ThemeSettings::default()
    };

    let scopes = rules
        .iter()
        .filter_map(|rule| {
            // A malformed selector would poison the whole theme, so a bad rule is skipped rather
            // than panicking inside a LazyLock (which would poison it for the process lifetime).
            let scope = ScopeSelectors::from_str(rule.selector).ok()?;
            Some(ThemeItem {
                scope,
                style: StyleModifier {
                    foreground: Some(rgb(rule.color)),
                    background: None,
                    font_style: rule.font,
                },
            })
        })
        .collect();

    Theme {
        name: Some(name.to_owned()),
        author: Some("Quill".to_owned()),
        settings,
        scopes,
    }
}

static DARCULA: LazyLock<Theme> = LazyLock::new(|| {
    build_theme(
        "Quill Darcula",
        DARCULA_FOREGROUND,
        DARCULA_BACKGROUND,
        DARCULA_RULES,
    )
});

static INTELLIJ_LIGHT: LazyLock<Theme> = LazyLock::new(|| {
    build_theme(
        "Quill Light",
        LIGHT_FOREGROUND,
        LIGHT_BACKGROUND,
        LIGHT_RULES,
    )
});

/// Returns the code-block colour scheme for the requested appearance.
pub fn code_theme(dark: bool) -> &'static Theme {
    if dark { &DARCULA } else { &INTELLIJ_LIGHT }
}

/// The scheme's default foreground, used for text no rule matched.
pub fn default_code_foreground(dark: bool) -> u32 {
    if dark {
        0xFF00_0000 | DARCULA_FOREGROUND
    } else {
        0xFF00_0000 | LIGHT_FOREGROUND
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packs_colors_in_argb_order() {
        assert_eq!(
            pack_argb(Color {
                r: 0x12,
                g: 0x34,
                b: 0x56,
                a: 0xFF
            }),
            0xFF12_3456
        );
    }

    #[test]
    fn builds_both_themes_with_every_rule() {
        // A typo in a selector silently drops a rule, which is invisible at runtime but shows up
        // here as a count mismatch.
        assert_eq!(code_theme(true).scopes.len(), DARCULA_RULES.len());
        assert_eq!(code_theme(false).scopes.len(), LIGHT_RULES.len());
    }

    #[test]
    fn themes_define_default_colors() {
        assert!(code_theme(true).settings.foreground.is_some());
        assert!(code_theme(false).settings.background.is_some());
    }

    #[test]
    fn style_span_rejects_empty_and_inverted_ranges() {
        assert!(StyleSpan::new(3, 3, EditorStyle::Text).is_none());
        assert!(StyleSpan::new(5, 2, EditorStyle::Text).is_none());
        assert!(StyleSpan::new(2, 5, EditorStyle::Text).is_some());
    }

    #[test]
    fn editor_style_ids_are_stable() {
        // These numbers are a wire contract with the JVM; changing one silently recolours the
        // editor, so they are pinned here.
        assert_eq!(EditorStyle::Text.id(), 0);
        assert_eq!(EditorStyle::Heading.id(), 1);
        assert_eq!(EditorStyle::LinkUrl.id(), 8);
        assert_eq!(EditorStyle::AutoLink.id(), 19);
    }
}
