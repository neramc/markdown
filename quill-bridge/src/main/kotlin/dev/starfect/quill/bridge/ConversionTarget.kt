package dev.starfect.quill.bridge

/**
 * A format the engine can translate a document into.
 *
 * Distinct from an *export*, and the difference is what makes these worth having: an exported HTML
 * file is a rendering nobody edits again, while a Confluence page or a Notion document is still a
 * document, which the target system then owns. Each conversion therefore has to produce that
 * system's own constructs — a Confluence code macro rather than a `<pre>`, three heading levels
 * rather than six — instead of markup that merely looks right.
 */
public enum class ConversionTarget(
    internal val id: Byte,
    public val displayName: String,
    /** What the result should be saved as. */
    public val extension: String,
    public val description: String,
) {
    /** Confluence storage format: XHTML carrying Confluence's own macros. */
    CONFLUENCE(
        0,
        "Confluence",
        "xml",
        "Storage format, with code blocks, callouts and task lists as Confluence macros",
    ),

    /** Markdown restricted to what Notion's importer understands. */
    NOTION(
        1,
        "Notion",
        "md",
        "Markdown in the subset Notion imports: three heading levels, no raw HTML, no footnotes",
    ),

    /** GFM, with every other dialect's syntax translated into something GitHub renders. */
    GITHUB_README(
        2,
        "GitHub README",
        "md",
        "GitHub Flavored Markdown, with other dialects' syntax translated into what GitHub renders",
    ),
}
