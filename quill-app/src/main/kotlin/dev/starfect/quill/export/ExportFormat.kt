package dev.starfect.quill.export

import dev.starfect.quill.bridge.ConversionTarget

/**
 * Everything a document can be turned into, and what each one is for.
 *
 * The list is not a menu of file types; it is a list of *destinations*, and the description on each
 * says which. Somebody exporting to PDF is sending a document to a person; somebody converting to
 * Confluence is putting it into a system that will own it afterwards. Those are different jobs and
 * they fail in different ways, which is why the descriptions here say what a format is good for
 * rather than what its extension is.
 */
public enum class ExportFormat(
    public val label: String,
    public val extension: String,
    public val description: String,
    /** Set when the engine performs this one as a document-to-document conversion. */
    public val conversion: ConversionTarget? = null,
) {
    HTML(
        "HTML",
        "html",
        "A standalone page with the styling baked in — opens anywhere, and needs nothing beside it",
    ),

    PDF(
        "PDF",
        "pdf",
        "Fixed pages with the fonts embedded, for sending to somebody who will read rather than edit",
    ),

    DOCX(
        "Word",
        "docx",
        "Headings, lists and tables as real Word styles, so the document can be edited and restyled",
    ),

    EPUB(
        "EPUB",
        "epub",
        "A reflowable book with a table of contents, for an e-reader",
    ),

    CONFLUENCE(
        "Confluence",
        "xml",
        "Storage format, with code blocks and callouts as macros Confluence's own editor understands",
        ConversionTarget.CONFLUENCE,
    ),

    NOTION(
        "Notion",
        "md",
        "Markdown in the subset Notion imports without leaving syntax visible on the page",
        ConversionTarget.NOTION,
    ),

    GITHUB_README(
        "GitHub README",
        "md",
        "GitHub Flavored Markdown, with other dialects' syntax translated into what GitHub renders",
        ConversionTarget.GITHUB_README,
    ),
    ;

    /** The file name this format suggests for a document called [stem]. */
    public fun fileNameFor(stem: String): String = when (this) {
        GITHUB_README -> "README.md"
        else -> "$stem.$extension"
    }
}
