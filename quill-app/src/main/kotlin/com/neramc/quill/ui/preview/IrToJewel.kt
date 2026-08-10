package com.neramc.quill.ui.preview

import com.neramc.quill.bridge.wire.AlertSeverity
import com.neramc.quill.bridge.wire.InlineIr
import com.neramc.quill.bridge.wire.MarkdownBlockIr
import com.neramc.quill.bridge.wire.TaskState
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.extensions.github.alerts.GitHubAlert
import org.jetbrains.jewel.markdown.extensions.github.strikethrough.GitHubStrikethroughNode

/**
 * Translates the engine's IR into Jewel's Markdown model.
 *
 * This is the seam that lets Rust own parsing while Jewel owns rendering: the engine produces a tree
 * shaped like `MarkdownBlock`/`InlineMarkdown`, and this mapper is a flat rename with no
 * restructuring. Jewel's own commonmark-based processor is never involved.
 */
public object IrToJewel {

    /** One item in the preview: either a Jewel block or something Quill renders itself. */
    public sealed interface PreviewItem {
        /** Line range in the source, used to synchronise scrolling with the editor. */
        public val lineStart: Int

        public data class Block(val block: MarkdownBlock, override val lineStart: Int) : PreviewItem

        /**
         * A GFM table.
         *
         * Jewel renders tables through an extension whose node type is not publicly constructible,
         * so Quill renders them itself rather than reaching into internals that could change.
         */
        public data class Table(val table: MarkdownBlockIr.Table, override val lineStart: Int) : PreviewItem
    }

    /** Converts the top level of a document into renderable items. */
    public fun toPreviewItems(blocks: List<MarkdownBlockIr>): List<PreviewItem> =
        blocks.mapNotNull { block ->
            when (block) {
                is MarkdownBlockIr.Table -> PreviewItem.Table(block, block.lineStart)
                else -> mapBlock(block)?.let { PreviewItem.Block(it, block.lineStart) }
            }
        }

    /** Converts one block, or `null` for a block with no Jewel equivalent. */
    public fun mapBlock(block: MarkdownBlockIr): MarkdownBlock? = when (block) {
        is MarkdownBlockIr.Paragraph -> MarkdownBlock.Paragraph(mapInlines(block.inlines))

        is MarkdownBlockIr.Heading -> MarkdownBlock.Heading(mapInlines(block.inlines), block.level.coerceIn(1, 6))

        is MarkdownBlockIr.BlockQuote -> MarkdownBlock.BlockQuote(mapBlocks(block.children))

        is MarkdownBlockIr.OrderedList -> MarkdownBlock.ListBlock.OrderedList(
            children = block.items.map(::mapListItem),
            isTight = block.tight,
            startFrom = block.startFrom,
            delimiter = block.delimiter,
        )

        is MarkdownBlockIr.UnorderedList -> MarkdownBlock.ListBlock.UnorderedList(
            children = block.items.map(::mapListItem),
            isTight = block.tight,
            marker = block.marker,
        )

        is MarkdownBlockIr.ListItem -> mapListItem(block)

        is MarkdownBlockIr.FencedCodeBlock ->
            MarkdownBlock.CodeBlock.FencedCodeBlock(content = block.content, language = block.language)

        is MarkdownBlockIr.IndentedCodeBlock -> MarkdownBlock.CodeBlock.IndentedCodeBlock(block.content)

        is MarkdownBlockIr.HtmlBlock -> MarkdownBlock.HtmlBlock(block.content)

        is MarkdownBlockIr.ThematicBreak -> MarkdownBlock.ThematicBreak

        is MarkdownBlockIr.Alert -> mapAlert(block)

        is MarkdownBlockIr.FootnoteDefinition -> MarkdownBlock.BlockQuote(
            listOf(MarkdownBlock.Paragraph(listOf(InlineMarkdown.Text("[^${block.name}]: ")))) +
                mapBlocks(block.children)
        )

        // Front matter is metadata, not content: the IDE hides it in the preview too.
        is MarkdownBlockIr.FrontMatter -> null

        // Tables are handled by the caller; rows and cells never appear at the top level.
        is MarkdownBlockIr.Table,
        is MarkdownBlockIr.TableRow,
        is MarkdownBlockIr.TableCell,
        -> null
    }

    private fun mapBlocks(blocks: List<MarkdownBlockIr>): List<MarkdownBlock> = blocks.mapNotNull(::mapBlock)

    private fun mapListItem(item: MarkdownBlockIr.ListItem): MarkdownBlock.ListItem {
        val children = mapBlocks(item.children)
        if (item.task == TaskState.NONE) {
            return MarkdownBlock.ListItem(children, item.level)
        }

        // Jewel's core list item has no checkbox, so the marker is prepended to the item's first
        // paragraph. Using the ballot characters rather than a literal "[x]" keeps the preview
        // looking rendered instead of showing raw source.
        val marker = if (item.task == TaskState.CHECKED) "☑ " else "☐ "
        val decorated = when (val first = children.firstOrNull()) {
            is MarkdownBlock.Paragraph ->
                listOf(MarkdownBlock.Paragraph(listOf(InlineMarkdown.Text(marker)) + first.inlineContent)) +
                    children.drop(1)
            else -> listOf(MarkdownBlock.Paragraph(listOf(InlineMarkdown.Text(marker)))) + children
        }
        return MarkdownBlock.ListItem(decorated, item.level)
    }

    private fun mapAlert(alert: MarkdownBlockIr.Alert): MarkdownBlock {
        val content = mapBlocks(alert.children)
        return when (alert.severity) {
            AlertSeverity.NOTE -> GitHubAlert.Note(content)
            AlertSeverity.TIP -> GitHubAlert.Tip(content)
            AlertSeverity.IMPORTANT -> GitHubAlert.Important(content)
            AlertSeverity.WARNING -> GitHubAlert.Warning(content)
            AlertSeverity.CAUTION -> GitHubAlert.Caution(content)
        }
    }

    /** Converts inline nodes. */
    public fun mapInlines(inlines: List<InlineIr>): List<InlineMarkdown> = inlines.map { inline ->
        when (inline) {
            is InlineIr.Text -> InlineMarkdown.Text(inline.content)
            is InlineIr.Emphasis -> InlineMarkdown.Emphasis(inline.delimiter, mapInlines(inline.children))
            is InlineIr.StrongEmphasis -> InlineMarkdown.StrongEmphasis(inline.delimiter, mapInlines(inline.children))
            is InlineIr.Strikethrough -> GitHubStrikethroughNode(inline.delimiter, mapInlines(inline.children))
            is InlineIr.Code -> InlineMarkdown.Code(inline.content)
            is InlineIr.Link -> InlineMarkdown.Link(inline.destination, inline.title, mapInlines(inline.children))
            is InlineIr.Image -> InlineMarkdown.Image(
                source = inline.source,
                alt = inline.alt,
                title = inline.title,
                inlineContent = mapInlines(inline.children),
            )
            is InlineIr.HtmlInline -> InlineMarkdown.HtmlInline(inline.content)
            is InlineIr.FootnoteReference -> InlineMarkdown.Text("[^${inline.name}]")
            InlineIr.SoftLineBreak -> InlineMarkdown.SoftLineBreak
            InlineIr.HardLineBreak -> InlineMarkdown.HardLineBreak
        }
    }
}
