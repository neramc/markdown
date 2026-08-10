//! Tag numbers for the Markdown IR.
//!
//! These mirror Jewel's `MarkdownBlock` / `InlineMarkdown` hierarchies so the Kotlin mapper is a
//! flat `when` over integers. The values are a wire contract with `BlockTag`/`InlineTag` on the JVM
//! side: append new tags, never renumber existing ones.

/// Block-level node tags.
pub mod block {
    pub const PARAGRAPH: u8 = 1;
    pub const HEADING: u8 = 2;
    pub const BLOCK_QUOTE: u8 = 3;
    pub const ORDERED_LIST: u8 = 4;
    pub const UNORDERED_LIST: u8 = 5;
    pub const LIST_ITEM: u8 = 6;
    pub const FENCED_CODE_BLOCK: u8 = 7;
    pub const INDENTED_CODE_BLOCK: u8 = 8;
    pub const HTML_BLOCK: u8 = 9;
    pub const THEMATIC_BREAK: u8 = 10;
    pub const TABLE: u8 = 11;
    pub const TABLE_ROW: u8 = 12;
    pub const TABLE_CELL: u8 = 13;
    pub const FRONT_MATTER: u8 = 14;
    pub const FOOTNOTE_DEFINITION: u8 = 15;
    pub const ALERT: u8 = 16;
}

/// Inline node tags.
pub mod inline {
    pub const TEXT: u8 = 1;
    pub const EMPHASIS: u8 = 2;
    pub const STRONG_EMPHASIS: u8 = 3;
    pub const CODE: u8 = 4;
    pub const LINK: u8 = 5;
    pub const IMAGE: u8 = 6;
    pub const HTML_INLINE: u8 = 7;
    pub const SOFT_LINE_BREAK: u8 = 8;
    pub const HARD_LINE_BREAK: u8 = 9;
    pub const STRIKETHROUGH: u8 = 10;
    pub const FOOTNOTE_REFERENCE: u8 = 11;
}

/// Column alignment for GFM tables.
pub mod alignment {
    pub const NONE: u8 = 0;
    pub const LEFT: u8 = 1;
    pub const CENTER: u8 = 2;
    pub const RIGHT: u8 = 3;
}

/// Task-list state carried on a list item.
///
/// Jewel's core `ListItem` has no checkbox concept, so the state is passed through and the Kotlin
/// mapper renders the marker itself rather than the engine faking one with literal text.
pub mod task {
    pub const NONE: u8 = 0;
    pub const UNCHECKED: u8 = 1;
    pub const CHECKED: u8 = 2;
}

/// GFM alert severities, matching Jewel's alert extension.
pub mod alert {
    pub const NOTE: u8 = 0;
    pub const TIP: u8 = 1;
    pub const IMPORTANT: u8 = 2;
    pub const WARNING: u8 = 3;
    pub const CAUTION: u8 = 4;
}
