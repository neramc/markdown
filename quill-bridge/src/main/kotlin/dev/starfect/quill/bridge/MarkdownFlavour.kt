package dev.starfect.quill.bridge

/**
 * The Markdown dialect a document is written in.
 *
 * The flavour changes both how the source is parsed and what the preview shows. It is a property of
 * the document rather than a global setting because a project routinely mixes them — a `README.md`
 * next to a `docs/guide.mdx` next to Markdoc content pages — and each has to render on its own terms.
 *
 * Setting it invalidates the document's cached derivations, so the preview, outline and statistics
 * all reflect the new dialect on the next request.
 */
public enum class MarkdownFlavour(internal val id: Byte, public val displayName: String) {

    /** The CommonMark specification with no extensions. */
    COMMON_MARK(0, "CommonMark"),

    /** CommonMark plus tables, task lists, strikethrough, autolinks, footnotes and alerts. */
    GFM(1, "GitHub Flavored Markdown"),

    /**
     * GFM plus JSX components and ESM `import`/`export`.
     *
     * The engine strips the module statements and expression braces before parsing, so the prose
     * around a component renders normally and the component itself appears as an element in the
     * preview rather than as literal text.
     */
    MDX(2, "MDX"),

    /**
     * GFM plus Markdoc's `{% tag %}` syntax.
     *
     * Tags are rewritten to elements carrying `data-markdoc` before parsing, which is what lets the
     * preview draw a callout or a partial as a block instead of showing the braces.
     */
    MARKDOC(3, "Markdoc"),
    ;

    public companion object {

        /** The flavour the engine reports, or [GFM] if the id is not one this build knows. */
        internal fun fromId(id: Int): MarkdownFlavour = entries.firstOrNull { it.id.toInt() == id } ?: GFM

        /**
         * The flavour a file extension implies, or `null` when the extension says nothing.
         *
         * Mirrors `Flavour::from_extension` in the engine; `.md` deliberately maps to [GFM] rather
         * than [COMMON_MARK] because that is what almost every `.md` file in the wild is.
         */
        public fun forExtension(extension: String): MarkdownFlavour? =
            when (extension.removePrefix(".").lowercase()) {
                "mdx" -> MDX
                "mdoc", "markdoc" -> MARKDOC
                "commonmark", "cmark" -> COMMON_MARK
                "md", "markdown", "mdown", "mkd", "mkdn", "text", "txt" -> GFM
                else -> null
            }

        /** The flavour a file name implies, defaulting to [GFM]. */
        public fun forFileName(fileName: String): MarkdownFlavour =
            forExtension(fileName.substringAfterLast('.', missingDelimiterValue = "")) ?: GFM
    }
}
