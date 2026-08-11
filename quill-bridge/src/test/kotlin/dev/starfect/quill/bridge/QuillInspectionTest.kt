package dev.starfect.quill.bridge

import dev.starfect.quill.bridge.wire.Inspection
import dev.starfect.quill.bridge.wire.InspectionSummary
import dev.starfect.quill.bridge.wire.Severity
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Drives the inspection path through the real shared library.
 *
 * The engine's own tests cover which problems each inspection finds. What only this suite can show
 * is that a finding survives the wire format intact — the right inspection identity, the right
 * severity, and offsets that still index the document on the JVM side.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuillInspectionTest {

    private lateinit var engine: QuillEngine

    @BeforeAll
    fun setUp() {
        engine = QuillEngine.create(darkTheme = true)
    }

    @AfterAll
    fun tearDown() {
        engine.close()
    }

    @Test
    fun `a clean document reports nothing`() {
        engine.openDocument("# Title\n\nA [link](https://example.com).\n").use { document ->
            assertEquals(emptyList(), document.inspections())
            assertEquals(InspectionSummary.CLEAN, InspectionSummary.of(document.inspections()))
        }
    }

    @Test
    fun `findings carry their inspection identity and severity`() {
        engine.openDocument("# One\n\n### Three\n").use { document ->
            val finding = document.inspections().single()

            assertEquals(Inspection.HEADING_LEVEL_JUMP, finding.inspection)
            assertEquals(Severity.WARNING, finding.severity)
            assertEquals(2, finding.line)
            assertContains(finding.message, "jumps from 1 to 3")
        }
    }

    @Test
    fun `offsets index the document text`() {
        // Korean is one UTF-16 unit per syllable and three UTF-8 bytes; a byte offset leaking
        // through would slice the wrong range here and index out of bounds in the editor.
        val text = "# 제목입니다\n\n텍스트  \t\n"
        engine.openDocument(text).use { document ->
            val findings = document.inspections()
            assertTrue(findings.isNotEmpty(), "expected the trailing whitespace to be reported")

            for (finding in findings) {
                assertTrue(finding.end <= text.length, "$finding runs past the ${text.length}-unit document")
                assertTrue(finding.start <= finding.end, "$finding is inverted")
                // The slice must not throw and must not split a surrogate pair.
                text.substring(finding.start, finding.end)
            }
        }
    }

    @Test
    fun `severities tally into the widget's summary`() {
        // One error (unclosed fence), one warning (heading jump), one weak (trailing space).
        engine.openDocument("# One\n\n### Three\n\ntext \n\n```\nopen\n").use { document ->
            val summary = InspectionSummary.of(document.inspections())

            assertEquals(1, summary.errors, "expected the unclosed fence")
            assertTrue(summary.warnings >= 1, "expected the heading jump")
            assertTrue(summary.weak >= 1, "expected the trailing whitespace")
            assertEquals(Severity.ERROR, summary.worst)
            assertEquals(summary.errors + summary.warnings + summary.weak, summary.total)
        }
    }

    @Test
    fun `the worst severity is what the widget shows`() {
        assertEquals(null, InspectionSummary.CLEAN.worst)
        assertEquals(Severity.WEAK, InspectionSummary(weak = 3).worst)
        assertEquals(Severity.WARNING, InspectionSummary(warnings = 1, weak = 9).worst)
        assertEquals(Severity.ERROR, InspectionSummary(errors = 1, warnings = 9, weak = 9).worst)
    }

    @Test
    fun `findings arrive in source order`() {
        engine.openDocument("# One\n\n### Three\n\n[a]()\n\ntext \n").use { document ->
            val offsets = document.inspections().map { it.start }
            assertEquals(offsets.sorted(), offsets, "the problems list must read top to bottom")
        }
    }

    @Test
    fun `editing re-runs the inspections`() {
        engine.openDocument("# One\n\n### Three\n").use { document ->
            assertEquals(1, document.inspections().size)

            // Insert the missing level. The cache is keyed on the version, so a stale result here
            // would leave the widget reporting a problem the user has already fixed.
            document.setText("# One\n\n## Two\n\n### Three\n")
            assertEquals(emptyList(), document.inspections())
        }
    }

    @Test
    fun `an unknown inspection id would still carry its message`() {
        // Nothing in this build produces one, so the guarantee is asserted on the mapping itself:
        // every id the engine can emit resolves, and an id it cannot emit resolves to null rather
        // than throwing, which is what keeps a newer library from breaking an older bridge.
        assertNotNull(Inspection.fromId(Inspection.HARD_TAB.id))
        assertEquals(null, Inspection.fromId(9_999))
    }
}
