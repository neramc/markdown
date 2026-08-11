package dev.starfect.quill.bridge.wire

/**
 * The HTML document tree the engine produces from rendered Markdown.
 *
 * The preview renders this rather than the Markdown block IR. Going through HTML is what makes the
 * flavour extensions work: a Markdoc tag or an MDX component becomes an ordinary element here, and
 * the renderer draws it without knowing which dialect produced it.
 *
 * The tree is deliberately minimal — a tag, ordered attributes and children. Entity references are
 * already decoded, and whitespace is preserved exactly as the renderer emitted it, so a `<pre>` in
 * the source is still a `<pre>` with its newlines intact by the time it reaches the screen.
 */
public sealed interface HtmlNode {

    /** A run of character data, with entities already decoded. */
    public data class Text(val text: String) : HtmlNode

    /**
     * An element.
     *
     * [tag] is lower-cased. [attributes] keeps document order because the renderer reads some of
     * them positionally — `class` before `data-markdoc`, for instance, when deciding how to draw a
     * Markdoc tag.
     */
    public data class Element(
        val tag: String,
        val attributes: List<HtmlAttribute>,
        val children: List<HtmlNode>,
    ) : HtmlNode {

        /** The first value for [name], or `null` when the element does not carry it. */
        public fun attribute(name: String): String? =
            attributes.firstOrNull { it.name == name }?.value

        /** The `class` attribute split on whitespace. */
        public val classes: List<String>
            get() = attribute("class")?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }.orEmpty()
    }
}

/** One attribute of an [HtmlNode.Element]. A bare attribute carries an empty [value]. */
public data class HtmlAttribute(val name: String, val value: String)

/** Concatenates the character data under [nodes], ignoring element structure. */
public fun textOf(nodes: List<HtmlNode>): String = buildString { appendText(this, nodes) }

private fun appendText(builder: StringBuilder, nodes: List<HtmlNode>) {
    for (node in nodes) {
        when (node) {
            is HtmlNode.Text -> builder.append(node.text)
            is HtmlNode.Element -> appendText(builder, node.children)
        }
    }
}
