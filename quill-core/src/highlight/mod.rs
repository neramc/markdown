//! Syntax highlighting.
//!
//! Two independent highlighters with different jobs: [`editor`] colours Markdown *source* as the
//! user types it, [`code`] colours the contents of fenced code blocks in the rendered preview.

pub mod code;
pub mod editor;
