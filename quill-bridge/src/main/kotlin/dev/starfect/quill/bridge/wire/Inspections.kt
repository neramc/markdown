package dev.starfect.quill.bridge.wire

/** How much a finding matters. Ordered, so `maxOrNull()` gives the worst present. */
public enum class Severity {
    /** A style note. Reported, never interrupts. */
    WEAK,

    /** Renders, but probably not the way the author meant. */
    WARNING,

    /** Will not render as intended. */
    ERROR,
    ;

    internal companion object {
        fun fromId(id: Int): Severity = entries.getOrElse(id) { WEAK }
    }
}

/**
 * Which inspection produced a finding.
 *
 * The names are the engine's stable identifiers, not display text — a future "suppress this
 * inspection" setting keys on them, so they outlive any wording change.
 */
public enum class Inspection(internal val id: Int, public val title: String) {
    EMPTY_LINK_DESTINATION(1, "Link or image with no destination"),
    MISSING_IMAGE_ALT(2, "Image without alternative text"),
    HEADING_LEVEL_JUMP(3, "Heading level skipped"),
    DUPLICATE_HEADING(4, "Duplicate heading"),
    UNLABELLED_CODE_FENCE(5, "Code fence without a language"),
    UNCLOSED_CODE_FENCE(6, "Unclosed code fence"),
    UNDEFINED_FOOTNOTE(7, "Undefined footnote"),
    UNUSED_FOOTNOTE(8, "Unused footnote"),
    UNDEFINED_LINK_REFERENCE(9, "Undefined link reference"),
    TABLE_COLUMN_MISMATCH(10, "Table row with the wrong number of cells"),
    HARD_TAB(11, "Hard tab used for indentation"),
    TRAILING_WHITESPACE(12, "Trailing whitespace"),
    MULTIPLE_TOP_LEVEL_HEADINGS(13, "More than one top-level heading"),
    BARE_URL(14, "Bare URL"),
    ;

    internal companion object {
        private val BY_ID = entries.associateBy { it.id }

        /**
         * The inspection with this id.
         *
         * An unknown id means the native library reports an inspection this bridge predates. That is
         * a version mismatch worth surfacing rather than a finding worth hiding, so it maps to a
         * placeholder that still carries the message the engine wrote.
         */
        fun fromId(id: Int): Inspection? = BY_ID[id]
    }
}

/**
 * One reported problem.
 *
 * [start] and [end] are UTF-16 offsets into the whole document, so a finding can be highlighted
 * without converting anything. [inspection] is null when the engine reports one this build does not
 * know; [message] is always populated, so such a finding still reads correctly in the list.
 */
public data class Finding(
    val inspection: Inspection?,
    val severity: Severity,
    val line: Int,
    val start: Int,
    val end: Int,
    val message: String,
)

/** Counts by severity, which is what the editor's inspection widget displays. */
public data class InspectionSummary(
    val errors: Int = 0,
    val warnings: Int = 0,
    val weak: Int = 0,
) {
    public val total: Int get() = errors + warnings + weak

    /** The worst severity present, or `null` when the document is clean. */
    public val worst: Severity?
        get() = when {
            errors > 0 -> Severity.ERROR
            warnings > 0 -> Severity.WARNING
            weak > 0 -> Severity.WEAK
            else -> null
        }

    public companion object {
        public val CLEAN: InspectionSummary = InspectionSummary()

        /** Tallies [findings] by severity. */
        public fun of(findings: List<Finding>): InspectionSummary = InspectionSummary(
            errors = findings.count { it.severity == Severity.ERROR },
            warnings = findings.count { it.severity == Severity.WARNING },
            weak = findings.count { it.severity == Severity.WEAK },
        )
    }
}
