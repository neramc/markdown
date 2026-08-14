package dev.starfect.quill

import dev.starfect.quill.bridge.QuillEngine
import dev.starfect.quill.io.RecentProjects
import dev.starfect.quill.io.SettingsStore
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Where the time goes before the first frame.
 *
 * Not an assertion — a measurement, printed. Optimising a startup path without one is how a morning
 * gets spent making the fast part faster.
 */
class StartupBenchmark {

    private fun ms(nanos: Long) = "%.1f ms".format(nanos / 1_000_000.0)

    @Test
    fun `where startup time goes`() {
        val engineNanos = measureNanoTime {
            QuillEngine.create(darkTheme = true).close()
        }

        val settingsNanos = measureNanoTime { SettingsStore().load() }
        val recentsNanos = measureNanoTime { RecentProjects().load() }

        // A second engine, to separate one-time class loading and native library loading from the
        // per-instance cost. The first number is what a user waits for; the second says how much of
        // it is unavoidable work rather than loading.
        val secondEngineNanos = measureNanoTime {
            QuillEngine.create(darkTheme = true).close()
        }

        println("BENCH engine.create(first)  = ${ms(engineNanos)}")
        println("BENCH engine.create(second) = ${ms(secondEngineNanos)}")
        println("BENCH settings.load         = ${ms(settingsNanos)}")
        println("BENCH recents.load          = ${ms(recentsNanos)}")
    }

    @Test
    fun `deriving a document for the first time`() {
        QuillEngine.create(darkTheme = true).use { engine ->
            val source = buildString {
                appendLine("# Heading")
                repeat(200) { appendLine("Some ordinary paragraph text number $it with **bold** and `code`.") }
                appendLine("```rust")
                appendLine("fn main() { println!(\"hi\"); }")
                appendLine("```")
            }

            engine.openDocument(source).use { document ->
                println("BENCH first blocks   = ${ms(measureNanoTime { document.blocks() })}")
                println("BENCH first html     = ${ms(measureNanoTime { document.htmlDom() })}")
                println("BENCH first outline  = ${ms(measureNanoTime { document.outline() })}")
                println("BENCH first spans    = ${ms(measureNanoTime { document.spans(0, 400) })}")
                println("BENCH second blocks  = ${ms(measureNanoTime { document.blocks() })}")
            }
        }
    }
}
